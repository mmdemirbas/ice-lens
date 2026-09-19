package cli

import kotlin.system.exitProcess

/**
 * `icelens <command> …` — see [IceLensCli.USAGE]. The exit code is the command's answer.
 *
 * The logging configuration is named before the first logger exists: the file is
 * `logback-cli.xml` rather than `logback.xml`, because this jar sits on the desktop app's
 * classpath too (the installers carry the command), and two `logback.xml`s on one classpath
 * are decided by jar order. The bundled launcher scripts set the same property.
 */
fun main(args: Array<String>) {
    System.setProperty("logback.configurationFile", "logback-cli.xml")
    exitProcess(IceLensCli.run(args.toList(), System.out, System.err))
}
