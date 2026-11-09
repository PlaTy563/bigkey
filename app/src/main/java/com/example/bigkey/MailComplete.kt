package com.example.bigkey

/**
 * メールアドレスのドメイン補完。
 * 入力中のアドレスに @ が含まれていたら、@ の後ろの打ちかけに合うドメインを返す。
 */
object MailComplete {
    // よく使う順
    private val domains = listOf(
        "gmail.com",
        "yahoo.co.jp",
        "icloud.com",
        "outlook.com",
        "yahoo.com",
        "hotmail.com",
        "docomo.ne.jp",
        "ezweb.ne.jp",
        "softbank.ne.jp",
        "au.com",
        "proton.me",
        "nifty.com",
    )

    /** 補完候補（ドメイン名そのもの）。@ が無ければ空、ドメインが完成していれば含めない。 */
    fun complete(address: String, limit: Int = 6): List<String> {
        val at = address.lastIndexOf('@')
        if (at < 0) return emptyList()
        val part = address.substring(at + 1).lowercase()
        val out = ArrayList<String>(limit)
        for (d in domains) {
            if (d.length > part.length && d.startsWith(part)) {
                out.add(d)
                if (out.size >= limit) break
            }
        }
        return out
    }
}
