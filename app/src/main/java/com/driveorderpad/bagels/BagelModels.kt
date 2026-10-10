package com.driveorderpad.bagels

import java.util.UUID
import kotlinx.serialization.Serializable

fun bagelId(): String = UUID.randomUUID().toString()
@Serializable enum class BagelKind(val label: String) { UNKNOWN("Packaging not specified"), INDIVIDUAL("Individual bagels"), DOZEN("Baker’s dozen") }
@Serializable enum class Slice(val label: String) { UNKNOWN("Slice not specified"), SLICED("Sliced"), UNSLICED("Unsliced") }
@Serializable enum class Toast(val label: String) { UNKNOWN("Toast not specified"), UNTOASTED("Untoasted"), TOASTED("Toasted"), DOUBLE("Double-toasted") }
@Serializable enum class SpreadSize(val label: String) { UNKNOWN("Size not specified"), SINGLE("Single"), TUB("Tub") }
@Serializable enum class CarStatus { UNCAPTURED, ORDER, NONE }
@Serializable data class BagelLine(val id: String = bagelId(), val flavor: String, val quantity: Int? = null, val slice: Slice = Slice.UNKNOWN, val toast: Toast = Toast.UNKNOWN)
@Serializable data class SpreadLine(val id: String = bagelId(), val flavor: String, val quantity: Int? = null, val size: SpreadSize = SpreadSize.UNKNOWN)
@Serializable data class BagelGroup(val id: String = bagelId(), val kind: BagelKind = BagelKind.UNKNOWN, val bagels: List<BagelLine> = emptyList(), val spreads: List<SpreadLine> = emptyList()) {
    val count: Int get() = bagels.sumOf { it.quantity ?: 0 }
}
@Serializable data class BagelCar(val id: String = bagelId(), val number: Int, val status: CarStatus = CarStatus.UNCAPTURED, val groups: List<BagelGroup> = emptyList(), val heard: String = "", val unresolved: List<String> = emptyList(), val entered: Boolean = false) {
    val count: Int get() = groups.sumOf { it.count }
    fun reviews(): List<String> = if (status == CarStatus.NONE) emptyList() else if (status == CarStatus.UNCAPTURED) listOf("Confirm no bagels or capture this car.") else buildList {
        addAll(unresolved)
        if (groups.isEmpty() || groups.all { it.bagels.isEmpty() }) add("No bagel flavors captured.")
        groups.forEach { g ->
            if (g.kind == BagelKind.UNKNOWN) add("Choose individual bagels or a baker’s dozen.")
            if (g.bagels.any { it.quantity == null }) add("Bagel quantity not specified.")
            if (g.bagels.any { it.slice == Slice.UNKNOWN || it.toast == Toast.UNKNOWN }) add("Some slicing / toasting not specified.")
            if (g.spreads.any { it.quantity == null || it.size == SpreadSize.UNKNOWN }) add("Some spread quantity / size not specified.")
            if (g.kind == BagelKind.DOZEN) {
                if (g.count != 13) add("Dozen has ${g.count} bagels; needs 13.")
                if (g.spreads.sumOf { it.quantity ?: 0 } != 2) add("Dozen needs 2 spread selections.")
            }
        }
    }.distinct()
}
@Serializable data class BagelNotebook(val schemaVersion: Int = 1, val revision: Long = 0, val orderRevision: Long = 0, val nextCarNumber: Int = 1, val cars: List<BagelCar> = emptyList(), val draft: String = "", val draftBaseRevision: Long = 0, val interrupted: Boolean = false)

data class BagelCatalog(val flavors: List<String>) {
    companion object {
        val spreads = listOf("Plain Cream Cheese", "Honey Walnut Cream Cheese")
        val fallback = BagelCatalog(listOf("Asiago Cheese", "Asiago Everything", "Blueberry", "Chocolate Chip", "Cinnamon Crunch", "Cinnamon Swirl & Raisin", "Everything", "Multigrain Bagel Flat", "Plain", "Sesame"))
    }
}

object BagelChanges {
    fun applyBatch(book: BagelNotebook, incoming: List<BagelCar>, expectedOrderRevision: Long): BagelNotebook {
        require(book.orderRevision == expectedOrderRevision) { "Orders changed while this batch was captured. Review the batch again before replacing them." }
        require(incoming.isNotEmpty()) { "No cars recognized. Keep the heard text and correct it." }
        require(incoming.map { it.number }.distinct().size == incoming.size)
        require(incoming.all { it.number in 1..1000 })
        val highest = incoming.maxOf { it.number }
        val existing = book.cars.associateBy { it.number }.toMutableMap()
        for (number in book.nextCarNumber..highest) existing.putIfAbsent(number, BagelCar(number = number))
        incoming.forEach { car ->
            val old = existing[car.number]
            require(old?.entered != true) { "Car ${car.number} was already entered. Use a new car number." }
            existing[car.number] = car.copy(id = old?.id ?: car.id)
        }
        return book.copy(cars = existing.values.sortedBy { it.number }, nextCarNumber = maxOf(book.nextCarNumber, highest + 1), draft = "", interrupted = false)
    }
    fun validate(book: BagelNotebook) {
        require(book.schemaVersion == 1) { "Unsupported bagel notes version; original notes have been kept." }
        require(book.cars.map { it.id }.distinct().size == book.cars.size)
        require(book.cars.map { it.number }.distinct().size == book.cars.size)
        require(book.nextCarNumber in 1..1001)
        book.cars.forEach { c ->
            require(c.number in 1..1000)
            require(c.status != CarStatus.NONE || c.groups.isEmpty())
            require(c.groups.map { it.id }.distinct().size == c.groups.size)
            c.groups.forEach { g ->
                require(g.bagels.all { it.quantity == null || it.quantity in 1..999 })
                require(g.spreads.all { it.quantity == null || it.quantity in 1..999 })
                require(g.bagels.map { it.id }.distinct().size == g.bagels.size)
                require(g.spreads.map { it.id }.distinct().size == g.spreads.size)
            }
        }
    }
}
