/**
 * Copyright (c) 2017 Oracle and/or its affiliates. All rights reserved.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.oracle.graaljs.nodewizard;

import com.oracle.graaljs.nodewizard.NodeJsJava.ServerCode;
import java.awt.EventQueue;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import net.java.html.BrwsrCtx;
import net.java.html.boot.script.Scripts;
import net.java.html.json.ComputedProperty;
import net.java.html.json.Function;
import net.java.html.json.Model;
import net.java.html.json.Models;
import net.java.html.json.OnPropertyChange;
import net.java.html.json.OnReceive;
import net.java.html.json.Property;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.api.java.platform.JavaPlatformManager;
import org.netbeans.api.java.platform.PlatformsCustomizer;
import org.netbeans.api.templates.TemplateRegistration;
import org.netbeans.html.boot.spi.Fn;
import org.netbeans.html.context.spi.Contexts;
import org.openide.awt.HtmlBrowser.URLDisplayer;
import org.openide.filesystems.FileObject;
import org.openide.util.Exceptions;
import org.openide.util.NbBundle.Messages;

@Model(className = "NodeJsJavaModel", instance = true, properties = {
    @Property(name = "current", type = String.class),
    @Property(name = "ok", type = boolean.class),
    @Property(name = "msg", type = String.class),
    @Property(name = "serverCode", type = ServerCode.class),
    @Property(name = "algJava", type = boolean.class),
    @Property(name = "algJS", type = boolean.class),
    @Property(name = "algRuby", type = boolean.class),
    @Property(name = "algR", type = boolean.class),
    @Property(name = "unitTesting", type = boolean.class),
    @Property(name = "graalvmPath", type = String.class),
    @Property(name = "graalvmCheck", type = Status.class),
    @Property(name = "archetypeVersions", type = String.class, array = true),
    @Property(name = "archetypeVersion", type = String.class),
    @Property(name = "missingLanguage", type = String.class),
    @Property(name = "working", type = boolean.class),
    @Property(name = "output", type = boolean.class),
    @Property(name = "processOutput", type = String.class),
})
public class NodeJsJava {
    private static final String ARCH_JAR_NAME = "nodejs-archetype.jar";
    private ScheduledExecutorService background;

    @TemplateRegistration(
            position = 133,
            page = "nodeJsJavaWizard.html",
            content = "nodeJsJava.archetype",
            folder = "Project/ClientSide",
            displayName = "#nodeJsJavaWizard",
            iconBase = "com/oracle/graaljs/nodewizard/nodeJava.png",
            description = "nodeJsJavaDescription.html"
    )
    @Messages("nodeJsJavaWizard=Node.js+Java Application")
    public static NodeJsJavaModel nodejsJavaAppWizard() throws IOException {
        NodeJsJavaModel data = new NodeJsJavaModel();
        findGraalVM(data);
        data.setUnitTesting(true);
        data.setServerCode(ServerCode.js);
        String localVersion;
        try {
            localVersion = findArchetypeVersion();
            data.setArchetypeVersion(localVersion);
            data.getArchetypeVersions().add(localVersion);
        } catch (IOException ex) {
            data.setMsg(ex.getLocalizedMessage());
        }
        data.searchArtifact("com.oracle.graal-js", "nodejs-archetype");
        return data;
    }

    private static void findGraalVM(NodeJsJavaModel data) {
        for (JavaPlatform p : JavaPlatformManager.getDefault().getInstalledPlatforms()) {
            FileObject fo = p.findTool("node");
            if (fo != null) {
                data.setGraalvmPath(fo.getParent().getParent().getPath());
                break;
            }
        }
    }
    private ScheduledFuture<?> graalVMCheck;
    private Process process;


    @Function
    public void chooseJDK(NodeJsJavaModel model) {
        if ("summary".equals(model.getCurrent())) {
            return;
        }
        EventQueue.invokeLater(() -> {
            PlatformsCustomizer.showCustomizer(null);
            findGraalVM(model);
        });
    }

    @Function
    public void download(NodeJsJavaModel model) throws MalformedURLException {
        if ("summary".equals(model.getCurrent())) {
            return;
        }
        final URL url = new URL("http://www.graalvm.org/");
        URLDisplayer.getDefault().showURL(url);
    }

    @ComputedProperty
    static int errorCode(String graalvmPath, Status graalvmCheck) {
        if (graalvmPath == null || !new File(new File(new File(graalvmPath), "bin"), "node").exists()) {
            return 1;
        }
        if (graalvmCheck == null) {
            return 4;
        }
        if (!"object".equals(graalvmCheck.getJava())) {
            return 5;
        }
        if (!"object".equals(graalvmCheck.getWorker_threads())) {
            return 3;
        }
        return 0;
    }

    @OnPropertyChange("graalvmPath")
    void checkGraalVM(NodeJsJavaModel model) {
        model.setGraalvmCheck(null);
        ScheduledFuture<?> previous = graalVMCheck;
        if (previous != null) {
            previous.cancel(true);
        }
        graalVMCheck = background().schedule(() -> {
            checkGraalVMNow(model);
        }, 1, TimeUnit.SECONDS);
    }

    private void checkGraalVMNow(NodeJsJavaModel model) {
        model.setWorking(true);
        Status status;
        try {
            status = testGraalVMVersion(model.getGraalvmPath());
        } catch (IOException | InterruptedException ex) {
            status = new Status().withLauncher(ex.getMessage());
        }
        model.setGraalvmCheck(status);
        model.setWorking(false);
    }

    private ScheduledExecutorService background() {
        if (background == null) {
            background = Executors.newSingleThreadScheduledExecutor();
        }
        return background;
    }

    @Function
    void installLanguage(NodeJsJavaModel model) {
        final String lang = model.getMissingLanguage();
        if (lang == null) {
            return;
        }
        background().execute(() -> {
            try {
                File gu = new File(new File(new File(model.getGraalvmPath()), "bin"), "gu");
                if (!gu.isFile()) {
                    model.setGraalvmCheck(new Status().withLauncher(gu + " not found"));
                }
                ProcessBuilder b = new ProcessBuilder(
                        gu.getPath(),
                        "install", lang);
                b.redirectErrorStream(true);
                model.setOutput(true);
                model.setProcessOutput("Running " + gu.getPath() + " install " + lang + "\n");
                Process p = b.start();
                process = p;
                final Appendable output = fillProcessOutput(model);
                int code;
                try {
                    drainProcessStream(p.getInputStream(), output, process);
                    code = p.waitFor();
                } finally {
                    process = null;
                }
                if (code != 0) {
                    output.append("\n\n" + gu + " install " + lang + " finished with code " + code);
                } else {
                    model.setMissingLanguage(null);
                    checkGraalVMNow(model);
                    selectLanguage(model, lang);
                    model.setOutput(false);
                }
            } catch (IOException | InterruptedException ex) {
                Exceptions.printStackTrace(ex);
            }
        });
    }

    private static Appendable fillProcessOutput(NodeJsJavaModel model) {
        return new Appendable() {
            @Override
            public Appendable append(CharSequence csq) throws IOException {
                return append(csq, 0, csq.length());
            }

            @Override
            public Appendable append(CharSequence csq, int start, int end) throws IOException {
                String text = model.getProcessOutput();
                final CharSequence newText = csq.subSequence(start, end);
                int backspaceCount = 0;
                while (backspaceCount < newText.length() && newText.charAt(backspaceCount) == '\b') {
                    backspaceCount++;
                }
                text = text.substring(0, text.length() - backspaceCount);
                text += newText;
                model.setProcessOutput(text);
                return this;
            }

            @Override
            public Appendable append(char c) throws IOException {
                return append(Character.toString(c));
            }
        };
    }

    @Function
    void processStop(NodeJsJavaModel model) {
        if (process != null) {
            process.destroy();
        } else {
            model.setOutput(false);
        }
    }

    @ComputedProperty
    static boolean labelJS(String missingLanguage) {
        return !"js".equals(missingLanguage);
    }

    @ComputedProperty
    static boolean labelR(String missingLanguage) {
        return !"R".equals(missingLanguage);
    }

    @ComputedProperty
    static boolean labelRuby(String missingLanguage) {
        return !"ruby".equals(missingLanguage);
    }

    @OnPropertyChange({ "algJS", "algR", "algRuby" })
    static void checkLanguageInstalled(NodeJsJavaModel model, String name) {
        final Status check = model.getGraalvmCheck();
        if (check == null) {
            return;
        }
        switch (name) {
            case "algJS":
                checkLanguageInstalled(model, "js", model::isAlgJS, check::isJs, model::setAlgJS);
                break;
            case "algR":
                checkLanguageInstalled(model, "R", model::isAlgR, check::isR, model::setAlgR);
                break;
            case "algRuby":
                checkLanguageInstalled(model, "ruby", model::isAlgRuby, check::isRuby, model::setAlgRuby);
                break;
            default:
                throw new IllegalStateException(name);
        }
    }

    static void selectLanguage(NodeJsJavaModel model, String name) {
        switch (name) {
            case "js":
                model.setAlgJS(true);
                break;
            case "R":
                model.setAlgR(true);
                break;
            case "ruby":
                model.setAlgRuby(true);
                break;
            default:
                throw new IllegalStateException(name);
        }
    }

    private static void checkLanguageInstalled(
        NodeJsJavaModel model,
        String language, Supplier<Boolean> getter, Supplier<Boolean> installed, Consumer<Boolean> setter
    ) {
        if (Boolean.TRUE.equals(getter.get())) {
            if (Boolean.TRUE.equals(installed.get())) {
                return;
            }
            setter.accept(false);
            model.setMissingLanguage(language);
        }
    }

    @Model(className = "Status", builder = "with", properties = {
        @Property(name = "launcher", type = String.class),
        @Property(name = "java", type = String.class),
        @Property(name = "js", type = boolean.class),
        @Property(name = "ruby", type = boolean.class),
        @Property(name = "R", type = boolean.class),
        @Property(name = "python", type = boolean.class),
        @Property(name = "worker_threads", type = String.class),
    })
    static class StatusCntrl {
    }

    static Status testGraalVMVersion(String path) throws IOException, InterruptedException {
        File nodeFile = new File(new File(new File(path), "bin"), "node");
        if (!nodeFile.isFile()) {
            return new Status().withLauncher(nodeFile + " not found");
        }
        ProcessBuilder b = new ProcessBuilder(
            nodeFile.getPath(),
            "--polyglot",
            "--use-classpath-env-var",
            "--experimental-worker",
            "--jvm",
            "-e",
            "function langCheck(lang) {\n"
          + "  try {\n"
          + "    return 42 == Polyglot.eval(lang, '42');\n"
          + "  } catch (e) {\n"
          + "    return false;\n"
          + "  }\n"
          + "}\n"
          + "console.log({\n"
          + "  'launcher' : null,\n"
          + "  'java' : typeof Java,\n"
          + "  'js' : langCheck('js'),\n"
          + "  'ruby' : langCheck('ruby'),\n"
          + "  'R' : langCheck('R'),\n"
          + "  'python' : langCheck('python'),\n"
          + "  'worker_threads' : typeof require('worker_threads')\n"
          + "});"
        );
        b.redirectErrorStream(true);
        Process p = b.start();
        InputStream is = p.getInputStream();
        StringBuilder sb = new StringBuilder();
        drainProcessStream(is, sb, p);
        Status status;
        final Fn.Presenter presenter = Scripts.createPresenter();
        Contexts.Builder contextBuilder = Contexts.newBuilder("xhr4j");
        contextBuilder.register(Fn.Presenter.class, presenter, 10);
        Contexts.fillInByProviders(NodeJsJava.class, contextBuilder);
        BrwsrCtx ctx = contextBuilder.build();
        try (Closeable c = Fn.activate(presenter)) {
            String out = sb.toString().trim();
            status = Models.parse(ctx, Status.class, new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8)));
        }
        return status;
    }

    private static void drainProcessStream(InputStream is, Appendable sb, Process p) throws IOException, InterruptedException {
        byte[] arr = new byte[1024];
        for (;;) {
            int len = is.read(arr);
            if (len == -1) {
                break;
            }
            sb.append(new String(arr, 0, len, "UTF-8"));
            p.waitFor(100, TimeUnit.MILLISECONDS);
        }
    }

    @ComputedProperty
    static String algorithmJava(boolean algJava) {
        return algJava ? "true" : "false";
    }

    @ComputedProperty
    static String algorithmJS(boolean algJS) {
        return algJS ? "true" : "false";
    }

    @ComputedProperty
    static String algorithmRuby(boolean algRuby) {
        return algRuby ? "true" : "false";
    }

    @ComputedProperty
    static String algorithmR(boolean algR) {
        return algR ? "true" : "false";
    }

    @ComputedProperty
    static String unitTest(boolean unitTesting) {
        return unitTesting ? "true" : "false";
    }

    @ComputedProperty
    static boolean anySample(boolean algJava, boolean algJS, boolean algRuby, boolean algR) {
        return algJava || algJS || algRuby || algR;
    }

    @ComputedProperty
    static String archetypeCatalog() {
        final String userHome = System.getProperty("user.home");
        return verifyArchetypeExists(userHome) ? "local" : "remote";
    }

    @OnReceive(url = "http://search.maven.org/solrsearch/select?q=g:{group}%20AND%20a:{artifact}&wt=json")
    static void searchArtifact(NodeJsJavaModel model, QueryResult result) {
        if (result != null && result.getResponse() != null) {
            for (QueryArtifact doc : result.getResponse().getDocs()) {
                if (
                    doc.getA().equals("nodejs-archetype") &&
                    doc.getG().equals("com.oracle.graal-js") &&
                    doc.getLatestVersion() != null
                ) {
                    final List<String> knownVersions = model.getArchetypeVersions();
                    final String lastest = doc.getLatestVersion();
                    model.setArchetypeVersion(lastest);
                    knownVersions.clear();
                    knownVersions.add(lastest);
                    try {
                        knownVersions.add(findArchetypeVersion());
                    } catch (IOException ex) {
                        // ignore
                    }
                    break;
                }
            }
        }
    }

    static boolean verifyArchetypeExists(final String userHome) {
        if (userHome == null) {
            return false;
        }
        File m2Repo = new File(new File(userHome, ".m2"), "repository");
        if (!m2Repo.isDirectory()) {
            return false;
        }
        final String version;
        try {
            version = findArchetypeVersion();
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
            return false;
        }
        final String baseName = "nodejs-archetype-" + version;
        final String jarName = baseName + ".jar";
        File archetype = new File(new File(new File(new File(new File(
            new File(m2Repo, "com"), "oracle"), "graal-js"), "nodejs-archetype"),
            version), jarName);
        File pom = new File(archetype.getParentFile(), baseName + ".pom");
        if (archetype.isFile() && pom.isFile()) {
            return true;
        }
        if (!archetype.getParentFile().mkdirs()) {
            return false;
        }
        InputStream is = NodeJsJavaModel.class.getResourceAsStream(ARCH_JAR_NAME);
        if (is == null) {
            return false;
        }
        try (FileOutputStream os = new FileOutputStream(archetype)) {
            copyStreams(is, os);
            is.close();
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
        }
        try (
            JarInputStream jar = new JarInputStream(NodeJsJavaModel.class.getResourceAsStream(ARCH_JAR_NAME));
            FileOutputStream os = new FileOutputStream(pom)
        ) {
            for (;;) {
                ZipEntry entry = jar.getNextEntry();
                if (entry == null) {
                    break;
                }
                if (entry.getName().endsWith("pom.xml")) {
                    copyStreams(jar, os);
                    break;
                }
                jar.closeEntry();
            }
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
        }
        return archetype.isFile() && pom.isFile();
    }

    static String findArchetypeVersion() throws IOException {
        try (
            final InputStream is = NodeJsJavaModel.class.getResourceAsStream(ARCH_JAR_NAME);
            final JarInputStream jar = new JarInputStream(ioIfNull(is));
        ) {
            for (;;) {
                ZipEntry entry = jar.getNextEntry();
                if (entry == null) {
                    break;
                }
                if (entry.getName().endsWith("pom.properties")) {
                    Properties p = new Properties();
                    p.load(jar);
                    return p.getProperty("version");
                }
                jar.closeEntry();
            }
            throw new FileNotFoundException("pom.properties not found");
        }
    }

    private static void copyStreams(InputStream is, final OutputStream os) throws IOException {
        byte[] arr = new byte[4096];
        for (;;) {
            int len = is.read(arr);
            if (len == -1) {
                break;
            }
            os.write(arr, 0, len);
        }
    }

    private static InputStream ioIfNull(InputStream is) throws IOException {
        if (is == null) {
            throw new FileNotFoundException("Cannot find bundled archetype");
        }
        return is;
    }

    enum ServerCode {
        js, java
    }
}
