package com.dotforge.calls

/**
 * A small phone number normaliser: turns what a caller ID or a user types into E.164 ("+447700900123"), using
 * the phone's region for national numbers. Calling codes and trunk prefixes for every region come from
 * libphonenumber's metadata (country calling codes are ITU assignments); the full library is not included.
 */
object PhoneNumbers {
    private class Region(val code: String, val trunk: String)

    /** "GB44/0" = region GB, calling code 44, national trunk prefix 0 (none when there is no "/"). */
    private const val TABLE =
        "AC247 AD376 AE971/0 AF93/0 AG1/1 AI1/1 AL355/0 AM374/0 AO244 AR54/0 AS1/1 AT43/0 AU61/0 AW297 " +
        "AX358/0 AZ994/0 BA387/0 BB1/1 BD880/0 BE32/0 BF226 BG359/0 BH973 BI257 BJ229 BL590/0 BM1/1 " +
        "BN673 BO591/0 BQ599 BR55/0 BS1/1 BT975 BW267 BY375/8 BZ501 CA1/1 CC61/0 CD243/0 CF236 CG242 " +
        "CH41/0 CI225 CK682 CL56 CM237 CN86/0 CO57/0 CR506 CU53/0 CV238 CW599 CX61/0 CY357 CZ420 DE49/0 " +
        "DJ253 DK45 DM1/1 DO1/1 DZ213/0 EC593/0 EE372 EG20/0 EH212/0 ER291/0 ES34 ET251/0 FI358/0 FJ679 " +
        "FK500 FM691 FO298 FR33/0 GA241 GB44/0 GD1/1 GE995/0 GF594/0 GG44/0 GH233/0 GI350 GL299 GM220 " +
        "GN224 GP590/0 GQ240 GR30 GT502 GU1/1 GW245 GY592 HK852 HN504 HR385/0 HT509 HU36/06 ID62/0 " +
        "IE353/0 IL972/0 IM44/0 IN91/0 IO246 IQ964/0 IR98/0 IS354 IT39 JE44/0 JM1/1 JO962/0 JP81/0 " +
        "KE254/0 KG996/0 KH855/0 KI686/0 KM269 KN1/1 KP850/0 KR82/0 KW965 KY1/1 KZ7/8 LA856/0 LB961/0 " +
        "LC1/1 LI423/0 LK94/0 LR231/0 LS266 LT370/0 LU352 LV371 LY218/0 MA212/0 MC377/0 MD373/0 ME382/0 " +
        "MF590/0 MG261/0 MH692/1 MK389/0 ML223 MM95/0 MN976/0 MO853 MP1/1 MQ596/0 MR222 MS1/1 MT356 " +
        "MU230 MV960 MW265/0 MX52 MY60/0 MZ258 NA264/0 NC687 NE227 NF672 NG234/0 NI505 NL31/0 NO47 " +
        "NP977/0 NR674 NU683 NZ64/0 OM968 PA507 PE51/0 PF689 PG675 PH63/0 PK92/0 PL48 PM508/0 PR1/1 " +
        "PS970/0 PT351 PW680 PY595/0 QA974 RE262/0 RO40/0 RS381/0 RU7/8 RW250/0 SA966/0 SB677 SC248 " +
        "SD249/0 SE46/0 SG65 SH290 SI386/0 SJ47 SK421/0 SL232/0 SM378 SN221 SO252/0 SR597 SS211/0 ST239 " +
        "SV503 SX1/1 SY963/0 SZ268 TA290 TC1/1 TD235 TG228 TH66/0 TJ992 TK690 TL670 TM993/8 TN216 TO676 " +
        "TR90/0 TT1/1 TV688 TW886/0 TZ255/0 UA380/0 UG256/0 US1/1 UY598/0 UZ998 VA39 VC1/1 VE58/0 VG1/1 " +
        "VI1/1 VN84/0 VU678 WF681 WS685 XK383/0 YE967/0 YT262/0 ZA27/0 ZM260/0 ZW263/0 "

    private val regions: Map<String, Region> = TABLE.trim().split(' ').associate { item ->
        val region = item.take(2)
        val rest = item.drop(2)
        region to Region(rest.substringBefore('/'), rest.substringAfter('/', ""))
    }

    /** The calling code of a two-letter region ("GB" to "44"), or null. */
    fun callingCode(region: String?): String? = region?.let { regions[it.uppercase()]?.code }

    /** True for text that can be a phone number: digits, spaces and + - . ( ) / only. */
    fun looksLikeNumber(raw: String): Boolean =
        raw.isNotBlank() && raw.all { it.isDigit() || it in "+-. ()/" } && raw.count { it.isDigit() } >= 3

    /**
     * [raw] in E.164, or null when it isn't a full phone number (letters, short codes, no region for a national
     * number). "+" and "00" (and "011" in the +1 area) start international numbers; anything else is national
     * in [region], whose trunk prefix ("0", "1", "8", "06") is dropped.
     */
    fun e164(raw: String, region: String?): String? {
        if (!looksLikeNumber(raw)) return null
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        val r = region?.let { regions[it.uppercase()] }
        val international = when {
            trimmed.startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            r?.code == "1" && digits.startsWith("011") -> digits.drop(3)
            else -> null
        }
        if (international != null) return international.takeIf { it.length in 7..15 && it[0] != '0' }?.let { "+$it" }
        r ?: return null
        var national = digits
        if (r.code == "1" && national.length == 11 && national.startsWith("1")) national = national.drop(1)
        else if (r.trunk.isNotEmpty() && national.startsWith(r.trunk)) national = national.drop(r.trunk.length)
        if (national.length < 6) return null
        val full = r.code + national
        return if (full.length in 7..15) "+$full" else null
    }

    /** A key to compare numbers by: E.164 when possible, else just the digits (short codes, odd formats). */
    fun key(raw: String, region: String?): String = e164(raw, region) ?: raw.filter { it.isDigit() }

    /**
     * True when [number] (E.164 or digits) starts with [prefix] as the user typed it: "+44" and "0044" are
     * international; a national prefix like "0800" or "800" is read in [region].
     */
    fun startsWith(number: String, prefix: String, region: String?): Boolean {
        val p = prefix.trim()
        if (p.isEmpty()) return false
        val pd = p.filter { it.isDigit() }
        if (pd.isEmpty()) return false
        val international = when {
            p.startsWith("+") -> pd
            pd.startsWith("00") -> pd.drop(2)
            else -> null
        }
        val n = number.removePrefix("+")
        if (international != null) return number.startsWith("+") && n.startsWith(international)
        val r = region?.let { regions[it.uppercase()] }
        // national: compare against the national part of the number
        if (r != null && number.startsWith("+" + r.code)) {
            val national = n.removePrefix(r.code)
            val bare = if (r.trunk.isNotEmpty() && pd.startsWith(r.trunk)) pd.drop(r.trunk.length) else pd
            return national.startsWith(bare)
        }
        return !number.startsWith("+") && n.startsWith(pd)
    }
}
