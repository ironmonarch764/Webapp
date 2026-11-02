package com.myweb.app

// Domain pelacak/analitik yang dikenal (cocok untuk domain itu dan semua subdomainnya). Sengaja pendek dan konservatif:
// hanya layanan yang tugasnya mengukur/melacak, bukan CDN atau SDK login. Diatur lewat saklar "Pelacak".
object Trackers {
    private val HOSTS = setOf(
        "google-analytics.com", "analytics.google.com", "googletagmanager.com", "stats.g.doubleclick.net",
        "hotjar.com", "hotjar.io", "mixpanel.com", "segment.io", "segment.com", "amplitude.com", "fullstory.com",
        "mouseflow.com", "crazyegg.com", "clarity.ms", "scorecardresearch.com", "quantserve.com", "chartbeat.com",
        "chartbeat.net", "statcounter.com", "histats.com", "mc.yandex.ru", "mc.yandex.com", "counter.yadro.ru",
        "hm.baidu.com", "cnzz.com", "bat.bing.com", "analytics.tiktok.com", "px.ads.linkedin.com", "snap.licdn.com",
        "ct.pinterest.com", "stats.wp.com", "pixel.wp.com", "nr-data.net", "newrelic.com", "heap.io", "heapanalytics.com",
        "optimizely.com", "kissmetrics.com", "luckyorange.com", "inspectlet.com", "clicky.com", "getclicky.com",
        "matomo.cloud", "pingdom.net", "omtrdc.net", "demdex.net", "bluekai.com", "krxd.net", "exelator.com"
    )

    fun isTracker(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in HOSTS) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }
}
