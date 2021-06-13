package com.github.minecraft_ta.totaldebug.codeViewer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.resolution.Resolvable;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserMethodDeclaration;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionMethodDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.minecraft_ta.totaldebug.DecompilationManager;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CompanionApp {

    public static final String COMPANION_APP_FOLDER = "companion-app";

    private static final Gson GSON = new GsonBuilder().create();

    private final Path appDir;
    private final Metafile metafile;

    private Process companionAppProcess;
    private Socket socket;
    private DataOutputStream outputStream;

    public CompanionApp(Path appDir) {
        this.appDir = appDir;

        if (!Files.exists(this.appDir)) {
            try {
                Files.createDirectory(this.appDir);
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Unable to create companion app directory", e);
            }
        }

        this.metafile = new Metafile(this.appDir.resolve(".meta"));
        this.metafile.read();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            //let's not keep this running
            if (this.companionAppProcess != null)
                this.companionAppProcess.destroyForcibly();
        }));
    }

    /**
     * Downloads, starts and connects to the companion app and sends progress updates to the player
     */
    public void startAndConnect() {
        ICommandSender sender = Minecraft.getMinecraft().player;

        if (!isRunning()) {
            this.metafile.loadNewestCompanionAppVersion();

            Path exePath = this.appDir.resolve("TotalDebugCompanion.exe");

            if (!Files.exists(exePath) ||
                !this.metafile.currentCompanionAppVersion.equals(this.metafile.newestCompatibleCompanionAppVersion)) {
                downloadCompanionApp(this.metafile.newestCompatibleCompanionAppVersion);
                this.metafile.currentCompanionAppVersion = this.metafile.newestCompatibleCompanionAppVersion;
                this.metafile.write();
            }


            sender.sendMessage(
                    new TextComponentTranslation("companion_app.starting")
                            .setStyle(new Style().setColor(TextFormatting.GRAY))
            );
            startApp();
        }

        if (!isConnected()) {
            sender.sendMessage(
                    new TextComponentTranslation("companion_app.connecting")
                            .setStyle(new Style().setColor(TextFormatting.GRAY))
            );
            if (connect(5, 1000)) {
                sender.sendMessage(
                        new TextComponentTranslation("companion_app.connection_success")
                                .setStyle(new Style().setColor(TextFormatting.GREEN))
                );
            } else {
                sender.sendMessage(
                        new TextComponentTranslation("companion_app.connection_fail")
                                .setStyle(new Style().setColor(TextFormatting.RED))
                );
            }
        }
    }

    /**
     * @return {@code true} if we're still connected to the companion app; {@code false} otherwise
     */
    public boolean isConnected() {
        if (this.socket == null || !this.socket.isConnected() || this.socket.isClosed())
            return false;

        try {
            synchronized (this.outputStream) {
                this.outputStream.write(0);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @return {@code true} if the companion app is running; {@code false} otherwise
     */
    public boolean isRunning() {
        return true;
//        return this.companionAppProcess != null && this.companionAppProcess.isAlive();
    }

    /**
     * Connects to the companion app
     *
     * @param retries the number of connection tries to do before giving up
     * @param delay   the delay between each try
     * @return {@code true} if the connection was successful; {@code false} otherwise
     */
    private boolean connect(int retries, int delay) {
        if (isConnected())
            return true;

        for (int i = 0; i < retries; i++) {
            try {
                Thread.sleep(delay);
                this.socket = new Socket();
                this.socket.connect(new InetSocketAddress(25570), 500);
                this.outputStream = new DataOutputStream(this.socket.getOutputStream());
                DataInputStream inputStream = new DataInputStream(this.socket.getInputStream());

                //receive thread
                new Thread(() -> {
                    while (isRunning() && isConnected()) {
                        try {
                            int id = inputStream.readUnsignedByte();
                            switch (id) {
                                case 1:
                                    String clazz = inputStream.readUTF();
                                    TotalDebug.PROXY.getDecompilationManager().openGui(Class.forName(clazz));
                                    break;
                                case 2:
                                    handleTextClickEvent(inputStream);
                                    break;
                                default:
                                    TotalDebug.LOGGER.error("Unknown packet id received from companion app: {}", id);
                                    break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }).start();
                return true;
            } catch (Exception e) {
                this.socket = null;
            }
        }

        return false;
    }

    private void handleTextClickEvent(DataInputStream inputStream) throws Throwable {
        Path decompilationDir = TotalDebug.PROXY.getDecompilationManager().getDecompilationDir();
        Path file = decompilationDir.resolve(inputStream.readUTF()).toAbsolutePath();
        if (!Files.exists(file)) {
            TotalDebug.LOGGER.error("Companion app sent file path that doesn't exist: {}", file.toString());
            return;
        }

        String code = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        int row = inputStream.readInt();
        int column = inputStream.readInt();
        Position position = new Position(row + 1, column + 1);

        PreParsedJavaParserTypeSolver typeSolver = new PreParsedJavaParserTypeSolver();
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8)
                .setSymbolResolver(new JavaSymbolSolver(new CombinedTypeSolver(typeSolver, new ReflectionTypeSolver(false))));

        JavaParser javaParser = new JavaParser(config);
        CompilationUnit unit = javaParser.parse(code).getResult().orElse(null);
        if (unit == null) {
            TotalDebug.LOGGER.error("Unable to parse java file requested by companion app {}", file.toString());
            return;
        }

        Node node = JavaParserHelper.getResolvableNodeAt(unit.findRootNode(), position);
        if (node == null)
            return;

        try {
            ReflectionMethodDeclaration method = (ReflectionMethodDeclaration) ((Resolvable<?>) node).resolve();

            String name = method.declaringType().getQualifiedName();
            TotalDebug.PROXY.getDecompilationManager().decompileClass(Class.forName(name));
            typeSolver.addCompilationUnit(
                    javaParser.parse(
                            new String(
                                    Files.readAllBytes(decompilationDir.resolve(name + ".java")),
                                    StandardCharsets.UTF_8
                            )
                    ).getResult().get()
            );

            JavaParserMethodDeclaration m = (JavaParserMethodDeclaration) ((Resolvable) JavaParserHelper.getResolvableNodeAt(javaParser.parse(code).getResult().get().findRootNode(), position)).resolve();
            TotalDebug.PROXY.getDecompilationManager().openGui(Class.forName(name), m.getWrappedNode().getRange().get().begin.line - 1);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Sends a request to the companion app to open the given file
     *
     * @param file the file to open
     * @throws IllegalStateException if {@link #isConnected()} returns false
     */
    public void sendOpenFileRequest(Path file, int row) {
        if (!isConnected())
            throw new IllegalStateException("Not connected");

        try {
            synchronized (this.outputStream) {
                this.outputStream.write(1);
                this.outputStream.writeUTF(file.toAbsolutePath().toString());
                this.outputStream.writeInt(row);
            }
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Error while sending open file request", e);
        }
    }

    public void sendReferenceSearchResults(String query, Collection<String> results, boolean methodSearch, int classesCount,
                                           int time) {
        if (!isConnected())
            throw new IllegalStateException("Not connected");

        try {
            synchronized (this.outputStream) {
                this.outputStream.write(2);
                this.outputStream.writeUTF(query);
                this.outputStream.writeInt(results.size());
                for (String result : results)
                    this.outputStream.writeUTF(result);

                this.outputStream.writeBoolean(methodSearch);
                this.outputStream.writeInt(classesCount);
                this.outputStream.writeInt(time);
            }
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Error while sending search results", e);
        }
    }

    /**
     * Starts the companion app
     */
    private void startApp() {
        if (isRunning())
            return;

        //TODO: linux
        Path exePath = this.appDir.resolve("TotalDebugCompanion.exe");
        Path logFile = createLogFile();

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    exePath.toAbsolutePath().toString(),
                    "\"" + this.appDir.getParent().resolve(DecompilationManager.DECOMPILED_FILES_FOLDER).toAbsolutePath() + "\""
            );

            if (logFile != null) {
                processBuilder.redirectError(logFile.toFile());
                processBuilder.redirectOutput(logFile.toFile());
            }

            this.companionAppProcess = processBuilder.start();
        } catch (IOException e) {
            this.companionAppProcess = null;
            TotalDebug.LOGGER.error("Unable to start companion app!", e);
        }
    }

    /**
     * Downloads the companion app release with the given {@code version} and unzips it into
     * the {@link #COMPANION_APP_FOLDER}.
     * Download progress updates are frequently sent to the player.
     *
     * @param version the version to download
     */
    private void downloadCompanionApp(String version) {
        Minecraft.getMinecraft().player.sendMessage(
                new TextComponentTranslation("companion_app.download_start", version)
                        .setStyle(new Style().setColor(TextFormatting.GRAY))
        );

        HttpClient client = HttpClients.createDefault();
        HttpResponse response;
        try {
            response = client.execute(new HttpGet("https://github.com/Minecraft-TA/TotalDebugCompanion/releases/download/" + version + "/TotalDebugCompanion.zip"));
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to reach github. Does this release exist? " + version, e);
            return;
        }

        HttpEntity entity = response.getEntity();

        long writtenBytes = 0;

        //download and unzip on the fly
        try (ZipInputStream zipInputStream = new ZipInputStream(Channels.newInputStream(Channels.newChannel(entity.getContent())))) {
            for (ZipEntry entry = zipInputStream.getNextEntry(); entry != null; entry = zipInputStream.getNextEntry()) {
                Path toPath = this.appDir.resolve(entry.getName());
                if (entry.isDirectory()) { //create directory
                    if (!Files.exists(toPath))
                        Files.createDirectory(toPath);
                } else { //transfer file to file system
                    try (FileChannel fileChannel = FileChannel.open(toPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                        fileChannel.transferFrom(Channels.newChannel(zipInputStream), 0, Long.MAX_VALUE);
                        writtenBytes += entry.getCompressedSize();
                    }

                    //send progress message
                    Minecraft.getMinecraft().player.sendStatusMessage(
                            new TextComponentString((writtenBytes * 100 / this.metafile.newestCompanionAppVersionSize) + "%")
                                    .setStyle(new Style().setColor(TextFormatting.GOLD))
                            , true);
                }
            }

            //fake 100% message because we won't exactly reach that
            Minecraft.getMinecraft().player.sendStatusMessage(
                    new TextComponentString(100 + "%")
                            .setStyle(new Style().setColor(TextFormatting.GOLD))
                    , true);

            TotalDebug.LOGGER.info("Successfully downloaded companion app version {}", version);
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to download and unzip file", e);
        }
    }

    @Nullable
    private Path createLogFile() {
        Path logDir = this.appDir.resolve("logs");

        if (!Files.exists(logDir)) {
            try {
                Files.createDirectory(logDir);
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Unable to create log directory for companion app", e);
                return null;
            }
        }

        Path newLogFile = logDir.resolve(LocalDateTime.now().format(
                new DateTimeFormatterBuilder()
                        .appendValue(ChronoField.DAY_OF_MONTH)
                        .appendLiteral('-')
                        .appendValue(ChronoField.MONTH_OF_YEAR)
                        .appendLiteral('-')
                        .appendValue(ChronoField.YEAR)
                        .appendLiteral('-')
                        .appendValue(ChronoField.HOUR_OF_DAY)
                        .appendLiteral('.')
                        .appendValue(ChronoField.MINUTE_OF_HOUR)
                        .appendLiteral('.')
                        .appendValue(ChronoField.SECOND_OF_MINUTE)
                        .toFormatter()
        ) + ".log");
        try {
            Files.createFile(newLogFile);
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to create log file for companion app", e);
            return null;
        }

        return newLogFile;
    }

    private static final class Metafile {
        /**
         * The newest version of the companion app that is compatible with the current TotalDebug version
         */
        private String newestCompatibleCompanionAppVersion;
        /**
         * The currently installed companion app version
         */
        private String currentCompanionAppVersion;
        /**
         * The download file size of the {@link #newestCompatibleCompanionAppVersion} release
         */
        private long newestCompanionAppVersionSize;

        /**
         * The path to the metadata file
         */
        private final Path path;

        private Metafile(Path path) {
            this.path = path;
        }

        /**
         * Reads the metadata file from the disk. If it doesn't exist, then {@link #initDefaultData()} is called.
         * Like {@link #initDefaultData()} the {@link #newestCompatibleCompanionAppVersion} is determined using
         * {@link #loadNewestCompanionAppVersion()}
         */
        public void read() {
            if (!Files.exists(path)) {
                initDefaultData();
                return;
            }

            try {
                List<String> lines = Files.readAllLines(path);

                if (lines.size() != 1) {
                    TotalDebug.LOGGER.error("Meta file does not contain 1 line");
                    Files.deleteIfExists(path);
                    initDefaultData();
                    return;
                }

                this.currentCompanionAppVersion = lines.get(0);
                loadNewestCompanionAppVersion();

                TotalDebug.LOGGER.info("Successfully loaded meta file. TotalDebug: {}, Companion: {}->{}",
                        TotalDebug.VERSION, this.currentCompanionAppVersion, this.newestCompatibleCompanionAppVersion);
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Unable to read meta file", e);
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ioException) {
                }

                initDefaultData();
            }
        }

        /**
         * Saves this instance to the disk
         */
        public void write() {
            try {
                Files.write(this.path, Lists.newArrayList(this.currentCompanionAppVersion));
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Unable to write meta file", e);
            }
        }

        /**
         * Initializes this instance with default data and then writes it to the disk:
         * <ul>
         * <li>{@link #newestCompatibleCompanionAppVersion} = {@link #loadNewestCompanionAppVersion()}</li>
         * <li>{@link #currentCompanionAppVersion} = {@link #newestCompatibleCompanionAppVersion}</li>
         * </ul>
         */
        private void initDefaultData() {
            loadNewestCompanionAppVersion();
            this.currentCompanionAppVersion = this.newestCompatibleCompanionAppVersion;
            write();
        }

        /**
         * Performs a github API request to find the newest compatible version with the
         * current {@link TotalDebug#VERSION} and sets {@link #newestCompatibleCompanionAppVersion}. Compatible means,
         * that major and minor versions match. This also gets the release file size for the found version and sets
         * {@link #newestCompanionAppVersionSize}
         * <br><br>
         * Example for TotalDebug: v1.2.5
         * <br>
         * CompanionApp:
         * <ul>
         *     <li>v1.3.0 - Compatible, but only used if no v1.2.X versions exist</li>
         *     <li>v1.2.8 - Newest compatible</li>
         *     <li>v1.2.0 - Compatible</li>
         *     <li>v1.1.5 - Not compatible</li>
         *     <li>v1.1.0 - Not compatible</li>
         * </ul>
         */
        private void loadNewestCompanionAppVersion() {
            try {
                HttpClient client = HttpClients.createDefault();
                HttpResponse response = client.execute(new HttpGet("https://api.github.com/repos/Minecraft-TA/TotalDebugCompanion/releases"));
                HttpEntity entity = response.getEntity();

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                entity.writeTo(outputStream);

                byte[] responseData = outputStream.toByteArray();

                JsonArray jsonArray = GSON.fromJson(new String(responseData, StandardCharsets.UTF_8), JsonArray.class);
                String totalDebugVersion = "v" + TotalDebug.VERSION;

                //find newest matching version
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonObject jsonObject = jsonArray.get(i).getAsJsonObject();
                    String version = jsonObject.get("tag_name").getAsString();

                    //don't compare build number
                    if (version.substring(0, version.lastIndexOf('.'))
                            .equals(totalDebugVersion.substring(0, totalDebugVersion.lastIndexOf('.')))) {
                        TotalDebug.LOGGER.info("Found matching companion app version {}", version);
                        this.newestCompatibleCompanionAppVersion = version;
                        this.newestCompanionAppVersionSize = jsonObject.getAsJsonArray("assets").get(0)
                                .getAsJsonObject().getAsJsonPrimitive("size").getAsLong();
                        return;
                    }
                }

                //return newest version if no matching version was found
                String newestVersion = jsonArray.get(0).getAsJsonObject().get("tag_name").getAsString();
                TotalDebug.LOGGER.info("No matching companion app version found. Falling back to newest available {}", newestVersion);
                this.newestCompatibleCompanionAppVersion = newestVersion;
                this.newestCompanionAppVersionSize = jsonArray.get(0).getAsJsonObject()
                        .getAsJsonArray("assets").get(0)
                        .getAsJsonObject().getAsJsonPrimitive("size").getAsLong();
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Could not determine newest companion app version. Auto-update or downloading might not work", e);
            }
        }
    }
}