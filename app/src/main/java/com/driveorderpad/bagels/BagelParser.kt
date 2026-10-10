package com.driveorderpad.bagels

/** A deliberately bounded interpreter. Every unsupported fragment remains on the car for review. */
class BagelParser(private val catalog: BagelCatalog) {
    private val numbers = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
    private fun normalize(text: String): String = text.lowercase().replace('’', '\'').let { raw ->
        Regex("\\b(${numbers.joinToString("|")})\\b").replace(raw) { numbers.indexOf(it.value).toString() }
    }
    private fun aliases(name: String): String = when (name) {
        "Cinnamon Swirl & Raisin", "Cinnamon Swirl Raisin" -> "cinnamon (?:swirl (?:and |& )?)?raisins?"
        "Asiago Cheese" -> "asiago(?: cheese)?"
        "Multigrain Bagel Flat" -> "multigrain(?: bagel)?(?: flat)?"
        else -> Regex.escape(name.lowercase())
    }
    private data class Match(val flavor: String, val range: IntRange)
    fun parse(transcript: String, nextCar: Int, current: List<BagelCar> = emptyList()): List<BagelCar> {
        require(transcript.isNotBlank()) { "No words captured. Try again or type the order." }
        val text = normalize(transcript)
        val markers = Regex("\\bcar\\s*(\\d+)\\b").findAll(text).toList()
        val segments = if (markers.isEmpty()) listOf(nextCar to text) else markers.mapIndexed { i, m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            n to text.substring(m.range.last + 1, markers.getOrNull(i + 1)?.range?.first ?: text.length)
        }
        require(segments.all { it.first in 1..1000 }) { "Use car numbers from 1 to 1000. Heard text is kept." }
        val results = linkedMapOf<Int, BagelCar>()
        segments.forEach { (n, body) ->
            val base = results[n] ?: current.firstOrNull { it.number == n }
            val car = parseCar(n, body, base)
            val earlier = results[n]
            results[n] = if (earlier != null && !isCorrection(body)) {
                earlier.copy(status = if (earlier.groups.isNotEmpty() || car.groups.isNotEmpty()) CarStatus.ORDER else car.status,
                    groups = earlier.groups + car.groups, heard = earlier.heard + " | " + car.heard,
                    unresolved = (earlier.unresolved + car.unresolved + "Car $n was mentioned more than once; check for duplicates.").distinct())
            } else car
        }
        val prefix = if (markers.isEmpty()) "" else text.substring(0, markers.first().range.first).replace(Regex("\\bfor\\b"), "").trim(' ', ',', '.')
        if (prefix.isNotEmpty()) {
            val first = results.keys.first()
            results[first] = results.getValue(first).copy(unresolved = results.getValue(first).unresolved + "Before first car: $prefix")
        }
        return results.values.toList()
    }
    private fun isCorrection(body: String) = Regex("\\b(make|change|remove|delete|add|actually|instead)\\b").containsMatchIn(body)
    private fun parseCar(number: Int, body: String, existing: BagelCar?): BagelCar {
        val notes = mutableListOf<String>()
        val used = BooleanArray(body.length)
        fun consume(range: IntRange) { range.forEach { if (it in used.indices) used[it] = true } }
        fun consumeRegex(pattern: String) { Regex(pattern).findAll(body).forEach { consume(it.range) } }
        val none = Regex("\\bno bagels?\\b").find(body)
        if (none != null && !Regex("cream|sesame|asiago|chocolate|cinnamon|blueberry|dozen|\\d+").containsMatchIn(body)) {
            return BagelCar(id = existing?.id ?: bagelId(), number = number, status = CarStatus.NONE, heard = body.trim())
        }
        if (none != null) notes += "No bagels conflicts with other heard details; review before clearing the order."
        val spreadMarker = Regex("\\b(?:plain cream(?: cheese)?|honey walnut(?: cream(?: cheese)?)?|cream cheese|(?:tubs?|singles?)\\s+(?:of\\s+)?plain)\\b").find(body)
        var spreadStart = spreadMarker?.range?.first ?: body.length
        if (spreadStart < body.length) {
            val before = body.substring(0, spreadStart)
            Regex("\\d+\\s*$").find(before)?.let { spreadStart = it.range.first }
        }
        val bagelText = body.substring(0, spreadStart)
        val found = mutableListOf<Match>()
        catalog.flavors.sortedByDescending { it.length }.forEach { flavor ->
            Regex("\\b${aliases(flavor)}\\b").findAll(bagelText).forEach { m ->
                if (found.none { m.range.first <= it.range.last && it.range.first <= m.range.last }) found += Match(flavor, m.range)
            }
        }
        found.sortBy { it.range.first }
        if (isCorrection(body) && existing != null && found.size == 1 && spreadStart == body.length) {
            val correction = correction(existing, body, found.single())
            if (correction != null) return correction.copy(heard = (existing.heard + " | " + body.trim()).trim(' ', '|'))
        }
        if (isCorrection(body) && !Regex("^\\W*add\\b").containsMatchIn(body)) {
            return (existing ?: BagelCar(number = number, status = CarStatus.ORDER)).copy(
                heard = ((existing?.heard ?: "") + " | " + body.trim()).trim(' ', '|'),
                unresolved = (existing?.unresolved.orEmpty() + "Correction needs manual review: ${body.trim()}").distinct())
        }
        val kind = when {
            Regex("\\b(?:baker'?s dozen|dozen)\\b").containsMatchIn(body) -> BagelKind.DOZEN
            Regex("\\bindividual\\b").containsMatchIn(body) -> BagelKind.INDIVIDUAL
            else -> BagelKind.UNKNOWN
        }
        consumeRegex("\\b(?:baker'?s dozen|dozen|individual)\\b")
        if (Regex("\\bdozen\\b").findAll(body).count() > 1) notes += "Multiple dozens heard; assign each group in Edit."
        val lines = found.mapIndexed { i, f ->
            consume(f.range)
            val beforeStart = found.getOrNull(i - 1)?.range?.last?.plus(1) ?: 0
            val before = body.substring(beforeStart, f.range.first)
            val quantityMatch = Regex("(\\d+)\\s*(?:x\\s*)?$").find(before)
            val quantity = quantityMatch?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..999 }
            if (quantityMatch != null) {
                consume((beforeStart + quantityMatch.range.first)..(beforeStart + quantityMatch.range.last))
                if (quantity == null) notes += "Bagel quantity out of range: ${quantityMatch.value}"
            }
            var afterEnd = found.getOrNull(i + 1)?.range?.first ?: spreadStart
            val afterRaw = body.substring(f.range.last + 1, afterEnd)
            val nextQuantity = Regex("\\d+\\s*$").find(afterRaw)
            if (nextQuantity != null) afterEnd = f.range.last + 1 + nextQuantity.range.first
            val prep = body.substring(f.range.last + 1, afterEnd)
            val slice = when {
                Regex("\\b(?:unsliced|not sliced|no slic(?:e|ing))\\b").containsMatchIn(prep) -> Slice.UNSLICED
                Regex("\\b(?:sliced|slice)\\b").containsMatchIn(prep) -> Slice.SLICED
                else -> Slice.UNKNOWN
            }
            val toast = when {
                Regex("\\b(?:untoasted|not toasted|no toast)\\b").containsMatchIn(prep) -> Toast.UNTOASTED
                Regex("\\bdouble[ -]toast(?:ed)?\\b").containsMatchIn(prep) -> Toast.DOUBLE
                Regex("\\btoast(?:ed)?\\b").containsMatchIn(prep) -> Toast.TOASTED
                else -> Toast.UNKNOWN
            }
            Regex("\\b(?:unsliced|not sliced|no slic(?:e|ing)|sliced|slice|untoasted|not toasted|no toast|double[ -]toast(?:ed)?|toast(?:ed)?)\\b").findAll(prep).forEach { m ->
                consume((f.range.last + 1 + m.range.first)..(f.range.last + 1 + m.range.last))
            }
            BagelLine(flavor = f.flavor, quantity = quantity, slice = slice, toast = toast)
        }
        val spreads = mutableListOf<SpreadLine>()
        if (spreadStart < body.length) {
            val sp = body.substring(spreadStart)
            val flavors = Regex("\\b(plain|honey walnut)\\b").findAll(sp).toList()
            flavors.forEachIndexed { i, m ->
                consume((spreadStart + m.range.first)..(spreadStart + m.range.last))
                val beforeStart = flavors.getOrNull(i - 1)?.range?.last?.plus(1) ?: 0
                val before = sp.substring(beforeStart, m.range.first)
                val count = Regex("(\\d+)\\s*(?:(?:tubs?|singles?|portions?)\\s*(?:of\\s*)?)?$").find(before)
                val q = count?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..999 }
                count?.let { consume((spreadStart + beforeStart + it.range.first)..(spreadStart + beforeStart + it.range.last)) }
                if (count != null && q == null) notes += "Spread quantity out of range: ${count.value}"
                val after = sp.substring(m.range.last + 1, flavors.getOrNull(i + 1)?.range?.first ?: sp.length)
                val sizePhrase = before + " " + after.replace(Regex("\\d+\\s*(?:tubs?|singles?)?\\s*(?:of)?\\s*$"), "")
                val size = when { Regex("\\btubs?\\b").containsMatchIn(sizePhrase) -> SpreadSize.TUB; Regex("\\bsingles?\\b").containsMatchIn(sizePhrase) -> SpreadSize.SINGLE; else -> SpreadSize.UNKNOWN }
                spreads += SpreadLine(flavor = if (m.value == "plain") BagelCatalog.spreads[0] else BagelCatalog.spreads[1], quantity = q, size = size)
            }
            consumeRegex("\\b(?:cream cheese|cream|cheese|tubs?|singles?|portions?)\\b")
            if (flavors.size > 1 && Regex("\\btub\\b").containsMatchIn(sp) && spreads.last().size == SpreadSize.UNKNOWN) notes += "Confirm whether tub applies to both spreads."
        }
        consumeRegex("\\b(?:for|and|with|of|a|an|the|bagels?|please|then|add|all)\\b")
        val leftover = body.mapIndexed { i, c -> if (used[i]) ' ' else c }.joinToString("").replace(Regex("[.,;:&]"), " ").replace(Regex("\\s+"), " ").trim()
        if (leftover.isNotEmpty()) notes += "Not interpreted: $leftover"
        var group = BagelGroup(kind = kind, bagels = lines, spreads = spreads)
        if (Regex("\\ball\\b").containsMatchIn(body)) {
            val last = lines.lastOrNull()
            if (last != null) group = group.copy(bagels = lines.map { it.copy(slice = if (last.slice != Slice.UNKNOWN) last.slice else it.slice, toast = if (last.toast != Toast.UNKNOWN) last.toast else it.toast) })
        }
        if (existing != null && Regex("^\\W*add\\b").containsMatchIn(body)) {
            return existing.copy(status = CarStatus.ORDER, groups = existing.groups + group, heard = existing.heard + " | " + body.trim(), unresolved = (existing.unresolved + notes).distinct())
        }
        return BagelCar(id = existing?.id ?: bagelId(), number = number, status = CarStatus.ORDER, groups = listOf(group), heard = body.trim(), unresolved = notes.distinct())
    }
    private fun correction(car: BagelCar, body: String, match: Match): BagelCar? {
        val candidates = car.groups.flatMap { g -> g.bagels.filter { it.flavor == match.flavor }.map { g.id to it } }
        if (candidates.size != 1) return null
        val (groupId, line) = candidates.single()
        val remove = Regex("\\b(?:remove|delete)\\b").containsMatchIn(body)
        val add = Regex("^\\W*add\\b").containsMatchIn(body)
        val quantity = Regex("\\b(\\d+)\\b").find(body)?.value?.toIntOrNull()?.takeIf { it in 1..999 }
        if (!remove && quantity == null) return null
        val safe = body.replace(Regex("\\b${aliases(match.flavor)}\\b"), "").replace(Regex("\\b(?:make|change|remove|delete|add|actually|instead|the|to|bagels?|please|\\d+)\\b"), "").trim(' ', ',', '.')
        if (safe.isNotEmpty()) return null
        val result = if (remove) null else line.copy(quantity = if (add) (line.quantity ?: return null) + checkNotNull(quantity) else quantity)
        if (result?.quantity?.let { it > 999 } == true) return null
        return car.copy(groups = car.groups.map { g -> if (g.id != groupId) g else g.copy(bagels = g.bagels.mapNotNull { if (it.id != line.id) it else result }) })
    }
}
