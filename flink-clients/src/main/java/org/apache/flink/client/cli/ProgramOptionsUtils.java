/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.client.cli;

import org.apache.flink.client.program.PackagedProgram;
import org.apache.flink.client.program.PackagedProgramUtils;
import org.apache.flink.configuration.Configuration;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

import static org.apache.flink.client.cli.CliFrontendParser.CLASS_OPTION;
import static org.apache.flink.client.cli.CliFrontendParser.PYARCHIVE_OPTION;
import static org.apache.flink.client.cli.CliFrontendParser.PYCLIENTEXEC_OPTION;
import static org.apache.flink.client.cli.CliFrontendParser.PYEXEC_OPTION;
import static org.apache.flink.client.cli.CliFrontendParser.PYFILES_OPTION;
import static org.apache.flink.client.cli.CliFrontendParser.PYMODULE_OPTION;
import static org.apache.flink.client.cli.CliFrontendParser.PYREQUIREMENTS_OPTION;
import static org.apache.flink.client.cli.CliFrontendParser.PYTHON_PATH;
import static org.apache.flink.client.cli.CliFrontendParser.PY_OPTION;

/** Utility class for {@link ProgramOptions}. */
public enum ProgramOptionsUtils {
    ;

    private static final Logger LOG = LoggerFactory.getLogger(ProgramOptionsUtils.class);

    /**
     * @return True if the commandline contains "-py" or "-pym" options or comes from PyFlink shell,
     *     false otherwise.
     */
    public static boolean isPythonEntryPoint(CommandLine line) {
        return line.hasOption(PY_OPTION.getOpt())
                || line.hasOption(PYMODULE_OPTION.getOpt())
                || "org.apache.flink.client.python.PythonGatewayServer"
                        .equals(line.getOptionValue(CLASS_OPTION.getOpt()));
    }

    /**
     * @return True if the commandline contains "-pyfs", "-pyarch", "-pyreq", "-pyexec", "-pypath"
     *     options, false otherwise.
     */
    public static boolean containsPythonDependencyOptions(CommandLine line) {
        return line.hasOption(PYFILES_OPTION.getOpt())
                || line.hasOption(PYREQUIREMENTS_OPTION.getOpt())
                || line.hasOption(PYARCHIVE_OPTION.getOpt())
                || line.hasOption(PYEXEC_OPTION.getOpt())
                || line.hasOption(PYCLIENTEXEC_OPTION.getOpt())
                || line.hasOption(PYTHON_PATH.getOpt());
    }

    public static ProgramOptions createPythonProgramOptions(CommandLine line)
            throws CliArgsException {
        try {
            ClassLoader classLoader = getPythonClassLoader();
            Class<?> pythonProgramOptionsClazz =
                    Class.forName(
                            "org.apache.flink.client.cli.PythonProgramOptions", false, classLoader);
            Constructor<?> constructor =
                    pythonProgramOptionsClazz.getConstructor(CommandLine.class);
            return (ProgramOptions) constructor.newInstance(line);
        } catch (InstantiationException
                | InvocationTargetException
                | NoSuchMethodException
                | IllegalAccessException
                | ClassNotFoundException e) {
            throw new CliArgsException(
                    "Python command line option detected but the flink-python module seems to be missing "
                            + "or not working as expected.",
                    e);
        }
    }

    private static ClassLoader getPythonClassLoader() {
        try {
            return new URLClassLoader(
                    new URL[] {PackagedProgramUtils.getPythonJar()},
                    Thread.currentThread().getContextClassLoader());
        } catch (RuntimeException e) {
            LOG.warn(
                    "An attempt to load the flink-python jar from the \"opt\" directory failed, "
                            + "fall back to use the context class loader.",
                    e);
            return Thread.currentThread().getContextClassLoader();
        }
    }

    public static void configurePythonExecution(
            Configuration configuration, PackagedProgram packagedProgram) throws Exception {
        final Options commandOptions = CliFrontendParser.getRunCommandOptions();
        final CommandLine commandLine =
                CliFrontendParser.parse(commandOptions, packagedProgram.getArguments(), true);
        final ProgramOptions programOptions = createPythonProgramOptions(commandLine);

        // Extract real program args by eliminating the PyFlink dependency options
        String[] programArgs = programOptions.extractProgramArgs(commandLine);
        // Set the real program args to the packaged program
        Field argsField = packagedProgram.getClass().getDeclaredField("args");
        argsField.setAccessible(true);
        argsField.set(packagedProgram, programArgs);

        // PyFlink dependency configurations are set in the pythonConfiguration when constructing
        // the program option,
        // we need to get the python configuration and merge with the execution configuration.
        Field pythonConfiguration =
                programOptions.getClass().getDeclaredField("pythonConfiguration");
        pythonConfiguration.setAccessible(true);
        ClassLoader classLoader = getPythonClassLoader();
        Class<?> pythonDependencyUtilsClazz =
                Class.forName(
                        "org.apache.flink.python.util.PythonDependencyUtils", false, classLoader);
        Method mergeMethod =
                pythonDependencyUtilsClazz.getDeclaredMethod(
                        "merge", Configuration.class, Configuration.class);
        mergeMethod.invoke(null, configuration, pythonConfiguration.get(programOptions));
    }
}
