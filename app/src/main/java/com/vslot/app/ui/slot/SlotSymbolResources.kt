package com.vslot.app.ui.slot

import androidx.annotation.DrawableRes
import com.vslot.app.R
import com.vslot.app.game.SlotTheme

object SlotSymbolResources {
    @DrawableRes
    fun image(theme: SlotTheme, symbol: String): Int {
        return image(theme, symbol, motionBlurred = false)
    }

    @DrawableRes
    fun spinImage(theme: SlotTheme, symbol: String): Int {
        return image(theme, symbol, motionBlurred = true)
    }

    @DrawableRes
    private fun image(theme: SlotTheme, symbol: String, motionBlurred: Boolean): Int {
        return when (theme) {
            SlotTheme.Violet -> violetImage(symbol, motionBlurred)
            SlotTheme.Roman -> romanImage(symbol, motionBlurred)
            SlotTheme.Neon -> neonImage(symbol, motionBlurred)
            SlotTheme.Pharaoh -> pharaohImage(symbol, motionBlurred)
            SlotTheme.Ocean -> oceanImage(symbol, motionBlurred)
        }
    }

    @DrawableRes
    private fun violetImage(symbol: String, motionBlurred: Boolean): Int {
        return when (symbol) {
            "v_wild" -> if (motionBlurred) R.drawable.vf_symbol_v_wild_spin_blur else R.drawable.vf_symbol_v_wild
            "diamond" -> if (motionBlurred) R.drawable.vf_symbol_diamond_spin_blur else R.drawable.vf_symbol_diamond
            "ruby" -> if (motionBlurred) R.drawable.vf_symbol_ruby_spin_blur else R.drawable.vf_symbol_ruby
            "coin" -> if (motionBlurred) R.drawable.vf_symbol_coin_spin_blur else R.drawable.vf_symbol_coin
            "crown" -> if (motionBlurred) R.drawable.vf_symbol_crown_spin_blur else R.drawable.vf_symbol_crown
            "star" -> if (motionBlurred) R.drawable.vf_symbol_star_spin_blur else R.drawable.vf_symbol_star
            "cherry" -> if (motionBlurred) R.drawable.vf_symbol_cherry_spin_blur else R.drawable.vf_symbol_cherry
            else -> if (motionBlurred) R.drawable.vf_symbol_bar_spin_blur else R.drawable.vf_symbol_bar
        }
    }

    @DrawableRes
    private fun romanImage(symbol: String, motionBlurred: Boolean): Int {
        return when (symbol) {
            "v_wild" -> if (motionBlurred) R.drawable.rr_symbol_v_wild_spin_blur else R.drawable.rr_symbol_v_wild
            "laurel" -> if (motionBlurred) R.drawable.rr_symbol_laurel_spin_blur else R.drawable.rr_symbol_laurel
            "shield" -> if (motionBlurred) R.drawable.rr_symbol_shield_spin_blur else R.drawable.rr_symbol_shield
            "column" -> if (motionBlurred) R.drawable.rr_symbol_column_spin_blur else R.drawable.rr_symbol_column
            "crown" -> if (motionBlurred) R.drawable.rr_symbol_crown_spin_blur else R.drawable.rr_symbol_crown
            "coin" -> if (motionBlurred) R.drawable.rr_symbol_coin_spin_blur else R.drawable.rr_symbol_coin
            "lightning" -> if (motionBlurred) R.drawable.rr_symbol_lightning_spin_blur else R.drawable.rr_symbol_lightning
            else -> if (motionBlurred) R.drawable.rr_symbol_gem_spin_blur else R.drawable.rr_symbol_gem
        }
    }

    @DrawableRes
    private fun neonImage(symbol: String, motionBlurred: Boolean): Int {
        return when (symbol) {
            "v_wild" -> if (motionBlurred) R.drawable.nn_symbol_v_wild_spin_blur else R.drawable.nn_symbol_v_wild
            "holo_chip" -> if (motionBlurred) R.drawable.nn_symbol_holo_chip_spin_blur else R.drawable.nn_symbol_holo_chip
            "neon_seven" -> if (motionBlurred) R.drawable.nn_symbol_neon_seven_spin_blur else R.drawable.nn_symbol_neon_seven
            "credit" -> if (motionBlurred) R.drawable.nn_symbol_credit_spin_blur else R.drawable.nn_symbol_credit
            "crown" -> if (motionBlurred) R.drawable.nn_symbol_crown_spin_blur else R.drawable.nn_symbol_crown
            "star" -> if (motionBlurred) R.drawable.nn_symbol_star_spin_blur else R.drawable.nn_symbol_star
            "cherry" -> if (motionBlurred) R.drawable.nn_symbol_cherry_spin_blur else R.drawable.nn_symbol_cherry
            else -> if (motionBlurred) R.drawable.nn_symbol_bar_spin_blur else R.drawable.nn_symbol_bar
        }
    }

    @DrawableRes
    private fun pharaohImage(symbol: String, motionBlurred: Boolean): Int {
        return when (symbol) {
            "v_wild" -> if (motionBlurred) R.drawable.pg_symbol_v_wild_spin_blur else R.drawable.pg_symbol_v_wild
            "scarab" -> if (motionBlurred) R.drawable.pg_symbol_scarab_spin_blur else R.drawable.pg_symbol_scarab
            "ankh" -> if (motionBlurred) R.drawable.pg_symbol_ankh_spin_blur else R.drawable.pg_symbol_ankh
            "coin" -> if (motionBlurred) R.drawable.pg_symbol_coin_spin_blur else R.drawable.pg_symbol_coin
            "crown" -> if (motionBlurred) R.drawable.pg_symbol_crown_spin_blur else R.drawable.pg_symbol_crown
            "sun" -> if (motionBlurred) R.drawable.pg_symbol_sun_spin_blur else R.drawable.pg_symbol_sun
            "lotus" -> if (motionBlurred) R.drawable.pg_symbol_lotus_spin_blur else R.drawable.pg_symbol_lotus
            else -> if (motionBlurred) R.drawable.pg_symbol_tablet_spin_blur else R.drawable.pg_symbol_tablet
        }
    }

    @DrawableRes
    private fun oceanImage(symbol: String, motionBlurred: Boolean): Int {
        return when (symbol) {
            "v_wild" -> if (motionBlurred) R.drawable.op_symbol_v_wild_spin_blur else R.drawable.op_symbol_v_wild
            "pearl" -> if (motionBlurred) R.drawable.op_symbol_pearl_spin_blur else R.drawable.op_symbol_pearl
            "trident" -> if (motionBlurred) R.drawable.op_symbol_trident_spin_blur else R.drawable.op_symbol_trident
            "coin" -> if (motionBlurred) R.drawable.op_symbol_coin_spin_blur else R.drawable.op_symbol_coin
            "crown" -> if (motionBlurred) R.drawable.op_symbol_crown_spin_blur else R.drawable.op_symbol_crown
            "starfish" -> if (motionBlurred) R.drawable.op_symbol_starfish_spin_blur else R.drawable.op_symbol_starfish
            "shell" -> if (motionBlurred) R.drawable.op_symbol_shell_spin_blur else R.drawable.op_symbol_shell
            else -> if (motionBlurred) R.drawable.op_symbol_anchor_spin_blur else R.drawable.op_symbol_anchor
        }
    }

    fun label(theme: SlotTheme, symbol: String): String {
        return when (theme) {
            SlotTheme.Violet -> when (symbol) {
                "v_wild" -> "Дикий символ V"
                "diamond" -> "Фиолетовый кристалл"
                "ruby" -> "Красный рубин"
                "coin" -> "Золотая монета"
                "crown" -> "Корона"
                "star" -> "Звезда"
                "cherry" -> "Вишня"
                else -> "Три полосы"
            }
            SlotTheme.Roman -> when (symbol) {
                "v_wild" -> "Дикий символ V"
                "laurel" -> "Лавровый венок"
                "shield" -> "Золотой щит"
                "column" -> "Мраморная колонна"
                "crown" -> "Императорская корона"
                "coin" -> "Золотая монета"
                "lightning" -> "Молния"
                else -> "Фиолетовый самоцвет"
            }
            SlotTheme.Neon -> when (symbol) {
                "v_wild" -> "Дикий символ V"
                "holo_chip" -> "Голографическая фишка"
                "neon_seven" -> "Неоновая семёрка"
                "credit" -> "Кредитный жетон"
                "crown" -> "Неоновая корона"
                "star" -> "Неоновая звезда"
                "cherry" -> "Неоновая вишня"
                else -> "Неоновый BAR"
            }
            SlotTheme.Pharaoh -> when (symbol) {
                "v_wild" -> "Дикий символ V"
                "scarab" -> "Золотой скарабей"
                "ankh" -> "Анкх"
                "coin" -> "Золотая монета"
                "crown" -> "Корона фараона"
                "sun" -> "Солнце пустыни"
                "lotus" -> "Лотос"
                else -> "Каменная табличка"
            }
            SlotTheme.Ocean -> when (symbol) {
                "v_wild" -> "Дикий символ V"
                "pearl" -> "Жемчужина"
                "trident" -> "Трезубец"
                "coin" -> "Золотая монета"
                "crown" -> "Морская корона"
                "starfish" -> "Морская звезда"
                "shell" -> "Ракушка"
                else -> "Якорь"
            }
        }
    }
}
