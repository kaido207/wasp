/*
 * Copyright (c) 1997, 2020 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.wasp.compiler;

import static javax.tools.ToolProvider.getSystemJavaCompiler;
import static org.glassfish.wasp.Constants.JSP_PACKAGE_NAME;
import static org.glassfish.wasp.compiler.ErrorDispatcher.createJavacError;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.CharArrayWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;

import org.glassfish.wasp.WaspException;
import org.glassfish.wasp.JspCompilationContext;

import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

/**
 * Invoke Java Compiler per JSR 199, using in-memory storage for both the input Java source and the generated bytecodes.
 *
 * @author Kin-man Chung
 */
public class Jsr199JavaCompiler implements JavaCompiler {

    protected List<File> cpath;
    private JspRuntimeContext rtctxt;
    protected ArrayList<BytecodeFile> classFiles;
    // a JSP compilation can produce multiple class files, we need to
    // keep track of all generated bytecodes..

    protected ArrayList<String> options = new ArrayList<>();
    protected CharArrayWriter charArrayWriter;
    private JspCompilationContext ctxt;
    protected String javaFileName;
    protected String javaEncoding;
    private ErrorDispatcher errDispatcher;

    @Override
    public void init(JspCompilationContext ctxt, ErrorDispatcher errDispatcher, boolean suppressLogging) {
        this.ctxt = ctxt;
        this.errDispatcher = errDispatcher;
        rtctxt = ctxt.getRuntimeContext();
        options.add("-proc:none"); // Disable annotation processing
    }

    @Override
    public void release() {
        classFiles = null; // release temp bytecodes
    }

    @Override
    public void setClassPath(List<File> path) {
        // Jsr199 does not expand jar manifest Class-Path (JDK bug?), we
        // need to do it here
        List<String> paths = new ArrayList<>();
        for (File f : path) {
            paths.add(f.toString());
        }
        List<String> files = JspUtil.expandClassPath(paths);
        this.cpath = new ArrayList<>();
        for (String file : files) {
            this.cpath.add(new File(file));
        }
    }

    @Override
    public void setExtdirs(String exts) {
        options.add("-extdirs");
        options.add(exts);
    }

    @Override
    public void setSourceVM(String sourceVM) {
        options.add("-source");
        options.add(sourceVM);
    }

    @Override
    public void setTargetVM(String targetVM) {
        options.add("-target");
        options.add(targetVM);
    }

    @Override
    public void saveClassFile(String className, String classFileName) {
        for (BytecodeFile bytecodeFile : classFiles) {
            String c = bytecodeFile.getClassName();
            String f = classFileName;
            if (!className.equals(c)) {
                // Compute inner class file name
                f = f.substring(0, f.lastIndexOf(File.separator) + 1) + c.substring(c.lastIndexOf('.') + 1) + ".class";
            }
            rtctxt.saveBytecode(c, f);
        }
    }

    @Override
    public void doJavaFile(boolean keep) throws WaspException {
        if (!keep) {
            charArrayWriter = null;
            return;
        }

        try {
            Writer writer = new OutputStreamWriter(new FileOutputStream(javaFileName), javaEncoding);
            writer.write(charArrayWriter.toString());
            writer.close();
            charArrayWriter = null;
        } catch (UnsupportedEncodingException ex) {
            errDispatcher.jspError("jsp.error.needAlternateJavaEncoding", javaEncoding);
        } catch (IOException ex) {
            throw new WaspException(ex);
        }
    }

    @Override
    public void setDebug(boolean debug) {
        if (debug) {
            options.add("-g");
        } else {
            options.add("-g:none");
        }
    }

    @Override
    public Writer getJavaWriter(String javaFileName, String javaEncoding) {
        this.javaFileName = javaFileName;
        this.javaEncoding = javaEncoding;
        this.charArrayWriter = new CharArrayWriter();
        return this.charArrayWriter;
    }

    @Override
    public long getClassLastModified() {
        String className = ctxt.getFullClassName();
        return rtctxt.getBytecodeBirthTime(className);
    }

    @Override
    public JavacErrorDetail[] compile(String className, Node.Nodes pageNodes) throws WaspException {
        String source = charArrayWriter.toString();
        classFiles = new ArrayList<>();

        javax.tools.JavaCompiler javac = getSystemJavaCompiler();
        if (javac == null) {
            errDispatcher.jspError("jsp.error.nojdk");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager stdFileManager = javac.getStandardFileManager(diagnostics, null, null);

        String name = className.substring(className.lastIndexOf('.') + 1);

        JavaFileObject[] sourceFiles = { new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return source;
            }
        } };

        try {
            stdFileManager.setLocation(StandardLocation.CLASS_PATH, this.cpath);
        } catch (IOException e) {
        }

        JavaFileManager javaFileManager = getJavaFileManager(stdFileManager);
        CompilationTask compilationTask = javac.getTask(null, javaFileManager, diagnostics, options, null, Arrays.asList(sourceFiles));

        try {
            javaFileManager.close();
        } catch (IOException ex) {
        }

        if (compilationTask.call()) {
            for (BytecodeFile bytecodeFile : classFiles) {
                rtctxt.setBytecode(bytecodeFile.getClassName(), bytecodeFile.getBytecode());
            }
            return null;
        }

        // There are compilation errors!
        List<JavacErrorDetail> problems = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            problems.add(createJavacError(
                javaFileName, pageNodes,
                new StringBuilder(diagnostic.getMessage(null)),
                (int) diagnostic.getLineNumber()));
        }

        return problems.toArray(new JavacErrorDetail[0]);
    }

    protected static class BytecodeFile extends SimpleJavaFileObject {

        private byte[] bytecode;
        private String className;

        BytecodeFile(URI uri, String className) {
            super(uri, Kind.CLASS);
            this.className = className;
        }

        String getClassName() {
            return this.className;
        }

        byte[] getBytecode() {
            return this.bytecode;
        }

        @Override
        public OutputStream openOutputStream() {
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    bytecode = this.toByteArray();
                }
            };
        }

        @Override
        public InputStream openInputStream() {
            return new ByteArrayInputStream(bytecode);
        }
    }

    protected JavaFileObject getOutputFile(final String className, final URI uri) {

        BytecodeFile classFile = new BytecodeFile(uri, className);

        // File the class file away, by its package name
        String packageName = className.substring(0, className.lastIndexOf("."));
        Map<String, Map<String, JavaFileObject>> packageMap = rtctxt.getPackageMap();
        Map<String, JavaFileObject> packageFiles = packageMap.get(packageName);
        if (packageFiles == null) {
            packageFiles = new ConcurrentHashMap<>();
            packageMap.put(packageName, packageFiles);
        }
        packageFiles.put(className, classFile);
        classFiles.add(classFile);

        return classFile;
    }

    protected JavaFileManager getJavaFileManager(JavaFileManager fm) {

        return new ForwardingJavaFileManager<JavaFileManager>(fm) {

            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, Kind kind, FileObject sibling) {
                return getOutputFile(className, URI.create("file:///" + className.replace('.', '/') + kind));
            }

            @Override
            public String inferBinaryName(Location location, JavaFileObject file) {
                if (file instanceof BytecodeFile) {
                    return ((BytecodeFile) file).getClassName();
                }

                return super.inferBinaryName(location, file);
            }

            @Override
            public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse) throws IOException {
                if (location == StandardLocation.CLASS_PATH && packageName.startsWith(JSP_PACKAGE_NAME)) {

                    // TODO: Need to handle the case where some of the classes
                    // are on disk

                    Map<String, JavaFileObject> packageFiles = rtctxt.getPackageMap().get(packageName);
                    if (packageFiles != null) {
                        return packageFiles.values();
                    }
                }

                return super.list(location, packageName, kinds, recurse);
            }
        };

    }
}
