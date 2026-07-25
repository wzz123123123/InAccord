package com.inforvans.accord.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

final class AccordCtlTest {
    @Test
    void helpUsesTheSinglePublicCommandRoot() {
        var output = new ByteArrayOutputStream();
        var command = new CommandLine(new AccordCtl());
        command.setOut(new PrintWriter(output, true));

        assertThat(command.execute("--help")).isZero();
        assertThat(output.toString()).contains("Usage: accordctl");
    }
}
