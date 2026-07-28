package com.vslot.app.ui

import java.text.NumberFormat
import java.util.Locale

private val coinFormatter = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU"))

fun Int.asCoins(): String = coinFormatter.format(this)

fun Long.asCoins(): String = coinFormatter.format(this)
