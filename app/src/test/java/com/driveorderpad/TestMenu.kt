package com.driveorderpad

import com.driveorderpad.domain.MenuCatalog
import java.io.File

fun testMenu(): MenuCatalog = MenuCatalog.decode(File("src/main/assets/menu.json").readText())
