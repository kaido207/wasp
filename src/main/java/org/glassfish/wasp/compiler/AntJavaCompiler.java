/*
 * Copyright (c) 1997, 2020 Oracle and/or its affiliates. All rights reserved.
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.glassfish.wasp.compiler;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DefaultLogger;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Javac;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.PatternSet;
import org.glassfish.wasp.JspCompilationContext;
import org.glassfish.wasp.Options;
import org.glassfish.wasp.WaspException;
import org.glassfish.wasp.util.SystemLogHandler;

/**
 * Java compiler through ant
 *
 * @author Kin-man Chung
 */
public class AntJavaCompiler implements JavaCompiler {
    
    // Use a threadpool and force it to 1 to simulate serialization
    private static ExecutorService threadPool;
    private static ThreadFactory threadFactory = new JavacThreadFactory();
    private static final String JAVAC_THREAD_PREFIX = "javac-";

    private static String lineSeparator = System.getProperty("line.separator");

    private WaspAntLogger logger;
    private Javac javac;
    private Project project;
    private JspCompilationContext ctxt;
    private Options options;
    private ErrorDispatcher errDispatcher;
    private String javaFileName;
    private String javaEncoding;
    private StringBuilder info = new StringBuilder();
    // For collecting Java compilation enviroment
    private Logger log;

  

    private Project getProject() {
        if (project != null) {
            return project;
        }

        // Initializing project
        project = new Project();
        logger = new WaspAntLogger();
        logger.setOutputPrintStream(System.out);
        logger.setErrorPrintStream(System.err);
        logger.setMessageOutputLevel(Project.MSG_INFO);
        project.addBuildListener(logger);
        if (System.getProperty("catalina.home") != null) {
            project.setBasedir(System.getProperty("catalina.home"));
        }

        if (options.getCompiler() != null) {
            if (log.isLoggable(FINE))
                log.fine("Compiler " + options.getCompiler());
            project.setProperty("build.compiler", options.getCompiler());
        }
        project.init();
        return project;
    }

    class WaspAntLogger extends DefaultLogger {

        private StringBuilder reportBuf = new StringBuilder();

        protected void printMessage(final String message, final PrintStream stream, final int priority) {
        }

        protected void log(String message) {
            reportBuf.append(message);
            reportBuf.append(lineSeparator);
        }

        protected String getReport() {
            String report = reportBuf.toString();
            reportBuf.setLength(0);
            return report;
        }
    }

    public void init(JspCompilationContext ctxt, ErrorDispatcher errDispatcher, boolean suppressLogging) {
        this.ctxt = ctxt;
        this.errDispatcher = errDispatcher;
        options = ctxt.getOptions();
        log = Logger.getLogger(AntJavaCompiler.class.getName());
        if (suppressLogging) {
            log.setLevel(Level.OFF);
        }
        getProject();
        javac = (Javac) project.createTask("javac");
        javac.setFork(options.getFork());
        
        // Set the Java compiler to use
        if (options.getCompiler() != null) {
            javac.setCompiler(options.getCompiler());
        }
        startThreadPool();
    }

    public void release() {
    }

    public void setExtdirs(String exts) {
        Path extdirs = new Path(project);
        extdirs.setPath(exts);
        javac.setExtdirs(extdirs);
        info.append("    extdirs=" + exts + "\n");
    }

    public void setTargetVM(String targetVM) {
        javac.setTarget(targetVM);
        info.append("   compilerTargetVM=" + targetVM + "\n");

    }

    public void setSourceVM(String sourceVM) {
        javac.setSource(sourceVM);
        info.append("   compilerSourceVM=" + sourceVM + "\n");
    }

    public void setClassPath(List<File> cpath) {
        Path path = new Path(project);
        for (File file : cpath) {
            path.setLocation(file);
            info.append("    cp=" + file + "\n");
        }
        javac.setClasspath(path);
    }

    public void saveClassFile(String className, String classFileName) {
        // class files are alwyas saved.
    }

    public void setDebug(boolean debug) {
        javac.setDebug(debug);
        javac.setOptimize(!debug);
    }

    public long getClassLastModified() {
        File classFile = new File(ctxt.getClassFileName());
        return classFile.lastModified();
    }

    public Writer getJavaWriter(String javaFileName, String javaEncoding) throws WaspException {

        this.javaFileName = javaFileName;
        info.append("Compile: javaFileName=" + javaFileName + "\n");

        this.javaEncoding = javaEncoding;

        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(javaFileName), javaEncoding);
        } catch (UnsupportedEncodingException ex) {
            errDispatcher.jspError("jsp.error.needAlternateJavaEncoding", javaEncoding);
        } catch (IOException ex) {
            errDispatcher.jspError("jsp.error.unableToCreateOutputWriter", javaFileName, ex);
        }
        return writer;
    }

    public JavacErrorDetail[] compile(String className, Node.Nodes pageNodes) throws WaspException {

        // Start capturing the System.err output for this thread
        SystemLogHandler.setThread();

        javac.setEncoding(javaEncoding);
        // Initialize sourcepath
        Path srcPath = new Path(project);
        srcPath.setLocation(options.getScratchDir());
        javac.setSrcdir(srcPath);
        info.append("    srcDir=" + srcPath + "\n");
        info.append("    work dir=" + options.getScratchDir() + "\n");

        // Build includes path
        PatternSet.NameEntry includes = javac.createInclude();
        includes.setName(ctxt.getJavaPath());
        info.append("    include=" + ctxt.getJavaPath() + "\n");

        BuildException be = null;
        StringBuilder errorReport = new StringBuilder();
        String errorCapture = null;
        if (ctxt.getOptions().getFork()) {
            try {
                javac.execute();
            } catch (BuildException e) {
                be = e;
                log.log(SEVERE, Localizer.getMessage("jsp.error.javac.exception"), e);
                log.log(SEVERE, Localizer.getMessage("jsp.error.javac.env", info.toString()));
            }
            errorReport.append(logger.getReport());
            // Stop capturing the System.err output for this thread
            errorCapture = SystemLogHandler.unsetThread();
        } else {
            errorReport.append(logger.getReport());
            errorCapture = SystemLogHandler.unsetThread();

            // Capture the current thread
            if (errorCapture != null) {
                errorReport.append(lineSeparator);
                errorReport.append(errorCapture);
            }

            JavacObj javacObj = new JavacObj(javac);
            synchronized (javacObj) {
                threadPool.execute(javacObj);
                // Wait for the thread to complete
                try {
                    javacObj.wait();
                } catch (InterruptedException e) {
                    ;
                }
            }
            be = javacObj.getException();
            if (be != null) {
                log.log(SEVERE, Localizer.getMessage("jsp.error.javac.exception"), be);
                log.log(SEVERE, Localizer.getMessage("jsp.error.javac.env", info.toString()));
            }
            errorReport.append(logger.getReport());
            errorCapture = javacObj.getErrorCapture();
        }

        if (errorCapture != null) {
            errorReport.append(lineSeparator);
            errorReport.append(errorCapture);
        }

        JavacErrorDetail[] javacErrors = null;
        if (be != null) {
            try {
                String errorReportString = errorReport.toString();
                javacErrors = ErrorDispatcher.parseJavacMessage(pageNodes, errorReportString, javaFileName);
            } catch (IOException ex) {
                throw new WaspException(ex);
            }
        }
        return javacErrors;
    }

    public void doJavaFile(boolean keep) {
        if (!keep) {
            File javaFile = new File(javaFileName);
            javaFile.delete();
        }
    }

    public static void startThreadPool() {
        if (threadPool == null) {
            threadPool = Executors.newCachedThreadPool(threadFactory);
        }
    }

    public static void shutdownThreadPool() {
        if (threadPool != null) {
            threadPool.shutdown();
        }
    }

    // Implement java compilation in a separate java thread to
    // avoid stack overflow problem (exposed by 64 -bit server)
    private static class JavacObj implements Runnable {

        Javac _javac = null;
        BuildException _be = null;
        String _errorCapture = null;

        public JavacObj(Javac javac) {
            _javac = javac;
        }

        public void run() {
            SystemLogHandler.setThread();
            try {
                _javac.execute();
            } catch (BuildException e) {
                _be = e;
            } finally {
                _errorCapture = SystemLogHandler.unsetThread();
                synchronized (this) {
                    this.notify();
                }
            }
        }

        public BuildException getException() {
            return _be;
        }

        public String getErrorCapture() {
            return _errorCapture;
        }
    }

    private static class JavacThreadFactory implements ThreadFactory {

        private ThreadFactory defaultFactory = Executors.defaultThreadFactory();

        public Thread newThread(Runnable r) {
            Thread t = defaultFactory.newThread(r);
            t.setName(JAVAC_THREAD_PREFIX + t.getName());
            return t;
        }
    }
}
