@file:Suppress("unused")
package android.util

/** Shim that routes Android log calls to stdout/stderr. */
object Log {
    @JvmStatic fun v(tag: String, msg: String): Int { println("V/$tag: $msg"); return 0 }
    @JvmStatic fun d(tag: String, msg: String): Int { println("D/$tag: $msg"); return 0 }
    @JvmStatic fun i(tag: String, msg: String): Int { println("I/$tag: $msg"); return 0 }
    @JvmStatic fun w(tag: String, msg: String): Int { System.err.println("W/$tag: $msg"); return 0 }
    @JvmStatic fun e(tag: String, msg: String): Int { System.err.println("E/$tag: $msg"); return 0 }
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable): Int { System.err.println("W/$tag: $msg\n$tr"); return 0 }
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable): Int { System.err.println("E/$tag: $msg\n$tr"); return 0 }
}
