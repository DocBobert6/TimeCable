package com.timecable.app

/** One row in the guide. `query` is an Internet Archive advanced-search query. */
data class ChannelDef(val number: Int, val callsign: String, val query: String)

object Lineup {
    private const val V = " AND mediatype:movies"

    // Edit freely: any archive.org collection works. Unknown collections just show NO SIGNAL.
    val channels = listOf(
        ChannelDef(301, "REEL", "collection:prelinger$V"),
        ChannelDef(302, "INKWELL", "collection:animationandcartoons$V"),
        ChannelDef(303, "RETROTV", "collection:classic_tv$V"),
        ChannelDef(304, "SPOTS", "collection:classic_tv_commercials$V"),
        ChannelDef(305, "SHADOW", "collection:Film_Noir$V"),
        ChannelDef(306, "HUSH", "collection:silent_films$V"),
        ChannelDef(307, "ORBIT", "collection:SciFi_Horror$V"),
        ChannelDef(308, "MATINEE", "collection:feature_films$V"),
    )
}
