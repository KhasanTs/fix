package com.example.utils

/**
 * Умный поиск, устойчивый к:
 *  - опечаткам (перестановка/пропуск/лишняя буква),
 *  - отсутствию или лишним знакам препинания,
 *  - разному регистру букв,
 *  - разным формам слова (падежи, число): "смерть" находит "смерти", "смертью" и т.д.
 *
 * Используется как замена простому String.contains() везде, где мы сравниваем
 * поисковый запрос пользователя с названием видео/канала — как для локальной
 * фильтрации (библиотека, история, офлайн-фолбэк), так и для результатов,
 * уже полученных от Rutube/VK/Дзен, чтобы не терять релевантные видео только
 * из-за того, что название не содержит запрос буква-в-букву.
 *
 * Ничего не удаляет из старого поведения: если строка находится обычным
 * contains() — это всегда считается совпадением. Приблизительное сравнение
 * только ДОБАВЛЯЕТ новые совпадения поверх старых.
 */
object SmartSearch {

    // Любая последовательность букв или цифр — знаки препинания, пробелы,
    // тире и т.п. автоматически становятся разделителями токенов.
    private val TOKEN_REGEX = Regex("[\\p{L}\\p{Nd}]+")

    /** Приводит текст к нижнему регистру и унифицирует "ё" -> "е" (частая опечатка/вариативность ввода). */
    fun normalize(text: String): String {
        return text.lowercase().replace('ё', 'е').trim()
    }

    /** Разбивает текст на слова-токены, игнорируя любые знаки препинания. */
    fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = normalize(text)
        return TOKEN_REGEX.findAll(normalized).map { it.value }.toList()
    }

    /**
     * Главная функция: true, если [query] можно считать найденным среди [targets]
     * (обычно это title, channel/author и т.п. одного видео).
     *
     * Пустой запрос считается совпадением для всех (как и раньше в коде приложения).
     */
    fun matches(query: String, vararg targets: String?): Boolean {
        val normQuery = normalize(query)
        if (normQuery.isBlank()) return true

        val joinedTarget = targets.filterNotNull().joinToString(" ") { normalize(it) }
        if (joinedTarget.isBlank()) return false

        // Быстрый путь и полная обратная совместимость: то, что раньше находилось
        // через contains(), находится и сейчас.
        if (joinedTarget.contains(normQuery)) return true

        val queryTokens = tokenize(normQuery)
        if (queryTokens.isEmpty()) return false

        val targetTokens = tokenize(joinedTarget)
        if (targetTokens.isEmpty()) return false

        // Каждое значимое слово запроса должно приблизительно найтись среди слов
        // цели — порядок слов и точная форма слова роли не играют.
        return queryTokens.all { qt -> targetTokens.any { tt -> tokensMatch(qt, tt) } }
    }

    /** Удобный вариант для двух конкретных значений (используется при сортировке/подсчёте очков не требуется). */
    fun matchesAny(query: String, targets: Iterable<String?>): Boolean {
        return matches(query, *targets.toList().toTypedArray())
    }

    private fun tokensMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val minLen = minOf(a.length, b.length)
        // Очень короткие слова (предлоги, союзы, инициалы) — приблизительное
        // сравнение слишком ненадёжно, требуем точного совпадения.
        if (minLen <= 2) return false

        if (stemLikeMatch(a, b)) return true
        if (typoTolerant(a, b)) return true
        return false
    }

    /**
     * Сравнение "по началу слова" с допуском на изменяющееся окончание —
     * покрывает падежи и числа в русском языке: "смерть"/"смерти"/"смертью",
     * "котопес"/"котопеса"/"котопесом" и т.д.
     */
    private fun stemLikeMatch(a: String, b: String): Boolean {
        val minLen = minOf(a.length, b.length)
        val prefix = commonPrefixLength(a, b)
        val allowedTail = when {
            minLen <= 5 -> 2
            minLen <= 8 -> 3
            else -> 4
        }
        return prefix >= 3 && prefix >= minLen - allowedTail
    }

    /**
     * Допуск на опечатки: пропущенная, лишняя, заменённая или переставленная
     * местами буква — через расстояние Левенштейна, ограниченное длиной слова.
     */
    private fun typoTolerant(a: String, b: String): Boolean {
        val minLen = minOf(a.length, b.length)
        val maxLen = maxOf(a.length, b.length)
        if (minLen < 4) return false
        if (maxLen - minLen > 3) return false

        val allowedDistance = when {
            maxLen <= 5 -> 1
            maxLen <= 9 -> 2
            else -> 3
        }
        return editDistance(a, b) <= allowedDistance
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    /** Классическое расстояние Левенштейна (итеративно, O(n*m)). */
    private fun editDistance(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n

        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)

        for (i in 1..n) {
            curr[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,       // удаление
                    curr[j - 1] + 1,   // вставка
                    prev[j - 1] + cost // замена
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[m]
    }
}
