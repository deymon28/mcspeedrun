package org.speedrun.speedrun;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

public class LogFileHandler extends Handler {
    private final PrintWriter writer;

    public LogFileHandler(String filePath) throws IOException {
        // 'true' for append mode
        this.writer = new PrintWriter(new FileWriter(filePath, true));
    }

    @Override
    public void publish(LogRecord record) {
        if (writer == null || !isLoggable(record)) {
            return;
        }
        // Simple formatter: [TIME] [LEVEL]: MESSAGE
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date(record.getMillis()));
        writer.println(String.format("[%s] [%s]: %s", timestamp, record.getLevel().getName(), record.getMessage()));
    }

    @Override
    public void flush() {
        if (writer != null) {
            writer.flush();
        }
    }

    @Override
    public void close() throws SecurityException {
        if (writer != null) {
            writer.close();
        }
    }
}