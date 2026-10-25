package io.github.evildarkarchon.andysdice.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The app's log (spec §6): {@code System.Logger} over {@code java.util.logging}, written to
 * {@code logs\andysdice.log} in the data folder with size-based rotation and no third-party framework.
 *
 * <p>{@link #install} replaces the root logger's handlers with the log file and tees {@code System.out} and
 * {@code System.err} into it line by line. The packaged launcher has no console, so without the tee what
 * libraries print there (Prism's pipeline report, LWJGL's loader messages, Jolt's start-up, an uncaught
 * exception's trace) would be lost; a dev run still sees it on the console. {@link #close} undoes all of it.
 *
 * <p>Thread contract: install and close on one thread, once each; logging from any thread in between.
 */
final class AppLog implements AutoCloseable {

    /** The live log file's name, in {@code logs\} under the data folder. */
    static final String FILE_NAME = "andysdice.log";

    /** The size the live file grows to before it rotates. */
    private static final long LIMIT_BYTES = 1L << 20;

    /** How many rotated files are kept beside the live one. */
    private static final int KEEP = 3;

    private final RotatingLogHandler handler;
    private final Logger root;
    private final Handler[] replacedHandlers;
    private final Level replacedLevel;
    private final PrintStream replacedOut;
    private final PrintStream replacedErr;

    private AppLog(RotatingLogHandler handler, Logger root, Handler[] replacedHandlers, Level replacedLevel,
            PrintStream replacedOut, PrintStream replacedErr) {
        this.handler = handler;
        this.root = root;
        this.replacedHandlers = replacedHandlers;
        this.replacedLevel = replacedLevel;
        this.replacedOut = replacedOut;
        this.replacedErr = replacedErr;
    }

    /**
     * Sends the JVM's logging, at {@code INFO} and above, and its standard output and error to the log file in a
     * data folder, creating {@code logs\} if needed.
     *
     * @param dataFolder the data folder chosen for this run
     * @return the installed log, to {@link #close} at exit (or in a test)
     * @throws IOException if the log folder or file can't be created
     */
    static AppLog install(Path dataFolder) throws IOException {
        Path logs = Files.createDirectories(dataFolder.resolve("logs"));
        RotatingLogHandler handler = new RotatingLogHandler(logs.resolve(FILE_NAME), LIMIT_BYTES, KEEP);
        handler.setFormatter(new LineFormatter());
        handler.setLevel(Level.INFO);

        Logger root = Logger.getLogger("");
        Handler[] replaced = root.getHandlers();
        for (Handler h : replaced) {
            // The default ConsoleHandler goes too: it writes to System.err, which is about to be teed into the
            // log, so every record would be logged twice.
            root.removeHandler(h);
        }
        Level replacedLevel = root.getLevel();
        root.addHandler(handler);
        root.setLevel(Level.INFO);

        PrintStream out = System.out;
        PrintStream err = System.err;
        System.setOut(tee(out, handler, "System.out", Level.INFO));
        // INFO, not WARNING: libraries print plain reports to System.err too (Prism's texture limits, say), and a
        // real problem printed there reads as one whatever its level. The logger name keeps the two apart.
        System.setErr(tee(err, handler, "System.err", Level.INFO));
        return new AppLog(handler, root, replaced, replacedLevel, out, err);
    }

    /**
     * The live log file.
     *
     * @return its path
     */
    Path file() {
        return handler.file();
    }

    /** Restores the standard streams and the root logger's handlers, and closes the log file. */
    @Override
    public void close() {
        System.out.flush();
        System.err.flush();
        System.setOut(replacedOut);
        System.setErr(replacedErr);
        root.removeHandler(handler);
        for (Handler h : replacedHandlers) {
            root.addHandler(h);
        }
        root.setLevel(replacedLevel);
        handler.close();
    }

    private static PrintStream tee(PrintStream console, Handler log, String source, Level level) {
        Charset charset = console.charset();
        return new PrintStream(new LineTee(console, log, source, level, charset), true, charset);
    }

    /**
     * Passes every byte on to the console stream it replaced, and hands each complete line to the log as a
     * record from {@code source}.
     */
    private static final class LineTee extends OutputStream {

        // A log handler that fails reports through ErrorManager, which prints to System.err: this stream. The
        // flag stops that report from being logged again, through the failing handler, without end.
        private static final ThreadLocal<Boolean> LOGGING = ThreadLocal.withInitial(() -> false);

        private final PrintStream console;
        private final Handler log;
        private final String source;
        private final Level level;
        private final Charset charset;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LineTee(PrintStream console, Handler log, String source, Level level, Charset charset) {
            this.console = console;
            this.log = log;
            this.source = source;
            this.level = level;
            this.charset = charset;
        }

        @Override
        public synchronized void write(int b) {
            console.write(b);
            if (b == '\n') {
                emit();
            } else {
                line.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            console.write(bytes, offset, length);
            int start = offset;
            for (int i = offset; i < offset + length; i++) {
                if (bytes[i] == '\n') {
                    line.write(bytes, start, i - start);
                    emit();
                    start = i + 1;
                }
            }
            line.write(bytes, start, offset + length - start);
        }

        @Override
        public synchronized void flush() {
            console.flush();
        }

        @Override
        public synchronized void close() {
            if (line.size() > 0) {
                emit();
            }
            console.flush();
        }

        private void emit() {
            String text = line.toString(charset);
            line.reset();
            if (text.endsWith("\r")) {
                text = text.substring(0, text.length() - 1);
            }
            if (LOGGING.get()) {
                return;
            }
            LOGGING.set(true);
            try {
                LogRecord record = new LogRecord(level, text);
                record.setLoggerName(source);
                log.publish(record);
            } finally {
                LOGGING.set(false);
            }
        }
    }

    /** One line per record: local time, level, logger, message, then the exception's trace if there is one. */
    private static final class LineFormatter extends Formatter {

        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault());

        @Override
        public String format(LogRecord record) {
            StringBuilder text = new StringBuilder(128)
                    .append(TIME.format(record.getInstant())).append(' ')
                    .append(record.getLevel().getName()).append(' ')
                    .append(record.getLoggerName()).append(": ")
                    .append(formatMessage(record)).append(System.lineSeparator());
            if (record.getThrown() != null) {
                StringWriter trace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(trace));
                text.append(trace);
            }
            return text.toString();
        }
    }
}
