package com.inforvans.accord.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "accordctl", mixinStandardHelpOptions = true,
        description = "Accord developer and operator commands")
public final class AccordCtl implements Callable<Integer> {
    public AccordCtl() {
    }

    @Override
    public Integer call() {
        return 0;
    }

    public static int execute(String... args) {
        return new CommandLine(new AccordCtl()).execute(args);
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }
}
