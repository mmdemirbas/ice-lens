package cli

import kotlin.system.exitProcess

/** `icelens <command> …` — see [IceLensCli.USAGE]. The exit code is the command's answer. */
fun main(args: Array<String>) {
    exitProcess(IceLensCli.run(args.toList(), System.out, System.err))
}
