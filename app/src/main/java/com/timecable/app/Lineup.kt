package com.timecable.app

/** One row in the guide. `query` is an Internet Archive advanced-search query. */
data class ChannelDef(val number: Int, val callsign: String, val query: String)

object Lineup {
    private const val V = " AND mediatype:movies"

    // Edit freely: any archive.org collection works. Unknown collections just show NO SIGNAL.
    val channels = listOf(
        ChannelDef(101, "VAULT", "collection:prelinger$V"),
        ChannelDef(102, "TOONS", "collection:animationandcartoons$V"),
        ChannelDef(103, "RERUN", "collection:classic_tv$V"),
        ChannelDef(104, "ADS", "collection:classic_tv_commercials$V"),
        ChannelDef(105, "NOIR", "collection:Film_Noir$V"),
        ChannelDef(106, "SILENT", "collection:silent_films$V"),
        ChannelDef(107, "SCIFI", "collection:SciFi_Horror$V"),
        ChannelDef(108, "CINEMA", "collection:feature_films$V"),
    )
}
