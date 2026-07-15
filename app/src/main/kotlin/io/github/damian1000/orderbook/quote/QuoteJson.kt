package io.github.damian1000.orderbook.quote

import io.github.damian1000.marketdata.model.Quote

/** The `/api/{symbol}/quote` response body — everything the topbar needs to label the instrument. */
fun Quote.toJson(): String =
    """{"symbol":${jsonString(symbol)},"name":${jsonString(instrument.name)},""" +
        """"exchange":${jsonString(instrument.exchange)},"currency":${jsonString(instrument.currency)},""" +
        """"last":${jsonString(last.toPlainString())},"change":${jsonString(change.toPlainString())},""" +
        """"changePercent":${jsonString(changePercent.toPlainString())},"marketOpen":$marketOpen,""" +
        """"asOfMillis":${asOf.toEpochMilli()}}"""

private fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
