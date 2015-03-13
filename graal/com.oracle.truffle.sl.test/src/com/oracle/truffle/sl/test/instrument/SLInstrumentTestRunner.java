/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.sl.test.instrument;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

import org.junit.*;
import org.junit.internal.*;
import org.junit.runner.*;
import org.junit.runner.manipulation.*;
import org.junit.runner.notification.*;
import org.junit.runners.*;
import org.junit.runners.model.*;

import com.oracle.truffle.api.instrument.*;
import com.oracle.truffle.api.instrument.impl.*;
import com.oracle.truffle.api.source.*;
import com.oracle.truffle.sl.factory.*;
import com.oracle.truffle.sl.nodes.instrument.*;
import com.oracle.truffle.sl.nodes.local.*;
import com.oracle.truffle.sl.parser.*;
import com.oracle.truffle.sl.runtime.*;
import com.oracle.truffle.sl.test.instrument.SLInstrumentTestRunner.InstrumentTestCase;

/**
 * This class builds and executes the tests for instrumenting SL. Although much of this class is
 * written with future automation in mind, at the moment the tests that are created are hard-coded
 * according to the file name of the test. To be automated, an automatic way of generating both the
 * node visitor and the node prober is necessary.
 *
 * Testing is done via JUnit via comparing execution outputs with expected outputs.
 */
public final class SLInstrumentTestRunner extends ParentRunner<InstrumentTestCase> {

    private static final String SOURCE_SUFFIX = ".sl";
    private static final String INPUT_SUFFIX = ".input";
    private static final String OUTPUT_SUFFIX = ".output";
    private static final String ASSIGNMENT_VALUE_SUFFIX = "_assnCount";

    private static final String LF = System.getProperty("line.separator");
    private static SLContext slContext;

    static class InstrumentTestCase {
        protected final Description name;
        protected final Path path;
        protected final String baseName;
        protected final String sourceName;
        protected final String testInput;
        protected final String expectedOutput;
        protected String actualOutput;

        protected InstrumentTestCase(Class<?> testClass, String baseName, String sourceName, Path path, String testInput, String expectedOutput) {
            this.name = Description.createTestDescription(testClass, baseName);
            this.baseName = baseName;
            this.sourceName = sourceName;
            this.path = path;
            this.testInput = testInput;
            this.expectedOutput = expectedOutput;
        }
    }

    private final List<InstrumentTestCase> testCases;

    public SLInstrumentTestRunner(Class<?> testClass) throws InitializationError {
        super(testClass);
        final SLStandardASTProber prober = new SLStandardASTProber();
        Probe.registerASTProber(prober);
        try {
            testCases = createTests(testClass);
        } catch (IOException e) {
            throw new InitializationError(e);
        } finally {
            Probe.unregisterASTProber(prober);
        }
    }

    @Override
    protected List<InstrumentTestCase> getChildren() {
        return testCases;
    }

    @Override
    protected Description describeChild(InstrumentTestCase child) {
        return child.name;
    }

    /**
     * Tests are created based on the files that exist in the directory specified in the passed in
     * annotation. Each test must have a source file and an expected output file. Optionally, each
     * test can also include an input file. Source files have an ".sl" extension. Expected output
     * have a ".output" extension. Input files have an ".input" extension. All these files must
     * share the same base name to be correctly grouped. For example: "test1_assnCount.sl",
     * "test1_assnCount.output" and "test1_assnCount.input" would all be used to create a single
     * test called "test1_assnCount".
     *
     * This method iterates over the files in the directory and creates a new InstrumentTestCase for
     * each group of related files. Each file is also expected to end with an identified at the end
     * of the base name to indicate what visitor needs to be attached. Currently, visitors are hard
     * coded to work on specific lines, so the code here is not currently generalizable.
     *
     * @param c The annotation containing the directory with tests
     * @return A list of {@link InstrumentTestCase}s to run.
     * @throws IOException If the directory is invalid.
     * @throws InitializationError If no directory is provided.
     *
     * @see #runChild(InstrumentTestCase, RunNotifier)
     */
    protected static List<InstrumentTestCase> createTests(final Class<?> c) throws IOException, InitializationError {
        SLInstrumentTestSuite suite = c.getAnnotation(SLInstrumentTestSuite.class);
        if (suite == null) {
            throw new InitializationError(String.format("@%s annotation required on class '%s' to run with '%s'.", SLInstrumentTestSuite.class.getSimpleName(), c.getName(),
                            SLInstrumentTestRunner.class.getSimpleName()));
        }

        String[] paths = suite.value();

        Path root = null;
        for (String path : paths) {
            root = FileSystems.getDefault().getPath(path);
            if (Files.exists(root)) {
                break;
            }
        }
        if (root == null && paths.length > 0) {
            throw new FileNotFoundException(paths[0]);
        }

        final Path rootPath = root;

        final List<InstrumentTestCase> testCases = new ArrayList<>();

        // Scaffolding in place for future automation
        Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path sourceFile, BasicFileAttributes attrs) throws IOException {
                String sourceName = sourceFile.getFileName().toString();
                if (sourceName.endsWith(SOURCE_SUFFIX)) {
                    String baseName = sourceName.substring(0, sourceName.length() - SOURCE_SUFFIX.length());

                    Path inputFile = sourceFile.resolveSibling(baseName + INPUT_SUFFIX);
                    String testInput = "";
                    if (Files.exists(inputFile)) {
                        testInput = readAllLines(inputFile);
                    }

                    Path outputFile = sourceFile.resolveSibling(baseName + OUTPUT_SUFFIX);
                    String expectedOutput = "";
                    if (Files.exists(outputFile)) {
                        expectedOutput = readAllLines(outputFile);
                    }

                    testCases.add(new InstrumentTestCase(c, baseName, sourceName, sourceFile, testInput, expectedOutput));

                }
                return FileVisitResult.CONTINUE;
            }
        });

        return testCases;
    }

    private static String readAllLines(Path file) throws IOException {
        // fix line feeds for non unix os
        StringBuilder outFile = new StringBuilder();
        for (String line : Files.readAllLines(file, Charset.defaultCharset())) {
            outFile.append(line).append(LF);
        }
        return outFile.toString();
    }

    /**
     * Executes the passed in test case. Instrumentation is added according to the name of the file
     * as explained in {@link #createTests(Class)}. Note that this code is not generalizable.
     */
    @Override
    protected void runChild(InstrumentTestCase testCase, RunNotifier notifier) {
        // TODO Current tests are hard-coded, automate this eventually
        notifier.fireTestStarted(testCase.name);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream printer = new PrintStream(out);
        final ASTProber prober = new SLStandardASTProber();
        Probe.registerASTProber(prober);
        try {
            // We use the name of the file to determine what visitor to attach to it.
            if (testCase.baseName.endsWith(ASSIGNMENT_VALUE_SUFFIX)) {
                // Set up the execution context for Simple and register our two listeners
                slContext = SLContextFactory.create(new BufferedReader(new StringReader(testCase.testInput)), printer);

                final Source source = Source.fromText(readAllLines(testCase.path), testCase.sourceName);
                Parser.parseSL(slContext, source);

                // Attach an instrument to every probe tagged as an assignment
                for (Probe probe : Probe.findProbesTaggedAs(StandardSyntaxTag.ASSIGNMENT)) {
                    SLPrintAssigmentValueListener slPrintAssigmentValueListener = new SLPrintAssigmentValueListener(printer);
                    probe.attach(Instrument.create(slPrintAssigmentValueListener, "SL print assignment value"));
                }

                SLFunction main = slContext.getFunctionRegistry().lookup("main");
                main.getCallTarget().call();
            } else {
                notifier.fireTestFailure(new Failure(testCase.name, new UnsupportedOperationException("No instrumentation found.")));
            }

            String actualOutput = new String(out.toByteArray());
            Assert.assertEquals(testCase.expectedOutput, actualOutput);
        } catch (Throwable ex) {
            notifier.fireTestFailure(new Failure(testCase.name, ex));
        } finally {
            Probe.unregisterASTProber(prober);
            notifier.fireTestFinished(testCase.name);
        }

    }

    public static void runInMain(Class<?> testClass, String[] args) throws InitializationError, NoTestsRemainException {
        JUnitCore core = new JUnitCore();
        core.addListener(new TextListener(System.out));
        SLInstrumentTestRunner suite = new SLInstrumentTestRunner(testClass);
        if (args.length > 0) {
            suite.filter(new NameFilter(args[0]));
        }
        Result r = core.run(suite);
        if (!r.wasSuccessful()) {
            System.exit(1);
        }
    }

    private static final class NameFilter extends Filter {
        private final String pattern;

        private NameFilter(String pattern) {
            this.pattern = pattern.toLowerCase();
        }

        @Override
        public boolean shouldRun(Description description) {
            return description.getMethodName().toLowerCase().contains(pattern);
        }

        @Override
        public String describe() {
            return "Filter contains " + pattern;
        }
    }

    /**
     * This sample listener provides prints the value of an assignment (after the assignment is
     * complete) to the {@link PrintStream} specified in the constructor. This listener can only be
     * attached at {@link SLWriteLocalVariableNode}, but provides no guards to protect it from being
     * attached elsewhere.
     */
    public final class SLPrintAssigmentValueListener extends DefaultInstrumentListener {

        private PrintStream output;

        public SLPrintAssigmentValueListener(PrintStream output) {
            this.output = output;
        }

        @Override
        public void returnValue(Probe probe, Object result) {
            output.println(result);
        }
    }

}
