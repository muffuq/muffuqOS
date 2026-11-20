package com.muffuq.os.stage

import java.util.Locale

/**
 * Words that get a picture next to them in the Visual style, Turkish and English. "stem*" also matches the
 * stem with suffixes (aşk → aşkım, aşkın…); stems are only marked where that can't catch an unrelated word.
 */
internal object EmojiMap {
    private val exact = HashMap<String, String>()
    private val stems = ArrayList<Pair<String, String>>()
    private val turkish = Locale.forLanguageTag("tr")

    init {
        add("❤️", "aşk*", "sevgi*", "seviyor*", "sevdim", "sevda*", "sevgili*", "kalp*", "kalb*", "gönül", "gönlüm*", "love*", "heart*", "lover*")
        add("💔", "ayrılık*", "kırık*", "kırıldı*", "broken", "heartbreak*")
        add("🔥", "ateş*", "alev*", "yanıyor*", "yandım", "yangın*", "fire*", "burn*", "flame*", "hot")
        add("🌙", "gece*", "ay", "night*", "moon*", "midnight")
        add("☀️", "güneş*", "sun", "sunny", "sunshine")
        add("⭐", "yıldız*", "star", "stars", "starlight")
        add("🌧️", "yağmur*", "rain*")
        add("🌊", "deniz*", "dalga*", "ocean*", "sea", "wave*")
        add("🌹", "gül", "gülüm", "rose*")
        add("😢", "ağla*", "gözyaş*", "cry*", "crying", "tear", "tears")
        add("😊", "gülüş*", "gülümse*", "gülüyor*", "smile*", "laugh*")
        add("💃", "dans*", "dance*", "dancing")
        add("🎵", "şarkı*", "müzik*", "nağme*", "song*", "music", "sing*")
        add("🚗", "araba*", "car", "cars", "drive*", "driving")
        add("💸", "para", "param", "paran", "parayı", "paralar*", "money", "cash", "dollar*")
        add("👑", "kral*", "kraliçe*", "king", "queen*")
        add("🌍", "dünya*", "world*")
        add("🏠", "ev", "evim", "evine", "evde", "home", "house")
        add("🍷", "şarap*", "rakı*", "kadeh*", "wine", "drink*", "drunk")
        add("🚬", "sigara*", "duman*", "smoke*")
        add("⏳", "zaman*", "time", "times")
        add("⏰", "saat*", "clock*")
        add("🌸", "bahar*", "çiçek*", "flower*", "spring")
        add("❄️", "kar", "kış*", "soğuk*", "snow*", "winter", "cold")
        add("💋", "öp*", "dudak*", "kiss*", "lips")
        add("👀", "göz", "gözler*", "gözlerin*", "gözüm*", "gözün*", "bakış*", "eye", "eyes", "look*")
        add("🖤", "siyah*", "kara", "karanlık*", "black", "dark*")
        add("💙", "mavi*", "blue")
        add("🌌", "gökyüzü*", "gök", "sky", "skies")
        add("🕊️", "özgür*", "freedom", "kanat*", "wing*")
        add("✈️", "uçak*", "uçuyor*", "fly*", "flying")
        add("💀", "ölüm*", "ölürüm", "öldüm", "death", "dead", "die", "dying")
        add("🙏", "allah*", "tanrı*", "dua*", "god", "pray*")
        add("🌃", "şehir*", "sokak*", "city", "street*")
        add("☕", "kahve*", "çay", "coffee")
        add("🍀", "şans*", "kader*", "luck*", "fate")
        add("🎉", "parti*", "party")
        add("💎", "elmas*", "diamond*")
        add("📞", "telefon*", "phone*", "call")
        add("🌅", "sabah*", "şafak*", "morning", "dawn", "sunrise")
        add("🏃", "koş*", "kaçıyor*", "kaçtım", "run", "running", "runaway")
        add("🤝", "dost*", "arkadaş*", "friend*")
        add("💞", "bebeğim", "baby", "babe", "darling", "sevgilim")
        add("😈", "şeytan*", "devil*")
        add("😇", "melek*", "angel*")
        add("💭", "rüya*", "hayal*", "dream*")
        add("😴", "uyku*", "uyuyor*", "sleep*")
        add("⚡", "şimşek*", "elektrik*", "electric*", "lightning", "energy")
        add("💪", "güçlü*", "strong*", "power*")
        add("🗝️", "anahtar*", "key", "keys")
        add("📸", "fotoğraf*", "photo*", "picture*")
        add("🌲", "orman*", "ağaç*", "forest*", "tree*")
        add("🐺", "kurt", "wolf", "wolves")
        add("🦋", "kelebek*", "butterfl*")
        add("💌", "mektup*", "letter*")
        add("🎸", "gitar*", "guitar*")
        add("🌈", "gökkuşağı*", "rainbow*")
        add("🌑", "yalnız*", "lonely", "alone")
        add("🕺", "oyna*")
    }

    fun lookup(word: String): String? {
        for (locale in arrayOf(turkish, Locale.ROOT)) {
            val w = word.lowercase(locale).filter { it.isLetter() }
            if (w.length < 2) continue
            exact[w]?.let { return it }
            stems.firstOrNull { w.startsWith(it.first) }?.let { return it.second }
        }
        return null
    }

    private fun add(emoji: String, vararg words: String) {
        for (w in words) {
            if (w.endsWith("*")) stems += w.dropLast(1) to emoji else exact[w] = emoji
        }
        // Longest stems first, so "gözyaş" wins over a shorter overlapping stem.
        stems.sortByDescending { it.first.length }
    }
}
