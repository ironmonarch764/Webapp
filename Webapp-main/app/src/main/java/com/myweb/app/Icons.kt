package com.myweb.app

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView

// Ikon menu dan pengaturan. Kunci diawali "@" (mis. "@perlindungan") = gambar di res/drawable-nodpi/ik_*.png.
// String lain dianggap emoji dan ditampilkan sebagai teks seperti sebelumnya. Referensi R.drawable ditulis langsung
// (bukan lewat nama) supaya tidak dibuang oleh penyusutan resource.
object Icons {
    private val map: Map<String, Int> = mapOf(
        "@menu" to R.drawable.ik_menu,
        "@situs" to R.drawable.ik_situs,
        "@refresh" to R.drawable.ik_refresh,
        "@home" to R.drawable.ik_home,
        "@autoscroll" to R.drawable.ik_autoscroll,
        "@filter_warna" to R.drawable.ik_filter_warna,
        "@perlindungan" to R.drawable.ik_perlindungan,
        "@akun" to R.drawable.ik_akun,
        "@tarik" to R.drawable.ik_tarik,
        "@pramuat" to R.drawable.ik_pramuat,
        "@gambar_depan" to R.drawable.ik_gambar_depan,
        "@unduhan" to R.drawable.ik_unduhan,
        "@gulir_ketuk" to R.drawable.ik_gulir_ketuk,
        "@kecepatan" to R.drawable.ik_kecepatan,
        "@mendarat" to R.drawable.ik_mendarat,
        "@blok_iklan" to R.drawable.ik_blok_iklan,
        "@blok_popup" to R.drawable.ik_blok_popup,
        "@pelacak" to R.drawable.ik_pelacak,
        "@cegah" to R.drawable.ik_cegah,
        "@sembunyi" to R.drawable.ik_sembunyi,
        "@perbarui" to R.drawable.ik_perbarui,
        "@gpc" to R.drawable.ik_gpc,
        "@amp" to R.drawable.ik_amp,
        "@url" to R.drawable.ik_url,
        "@sidik" to R.drawable.ik_sidik,
        "@cookie" to R.drawable.ik_cookie,
        "@https" to R.drawable.ik_https,
        "@dns" to R.drawable.ik_dns,
        "@diagnostik" to R.drawable.ik_diagnostik,
        "@pengaturan" to R.drawable.ik_pengaturan,
        "@edit" to R.drawable.ik_edit,
        "@page" to R.drawable.ik_page,
        "@batas_page" to R.drawable.ik_batas_page,
        "@view_grid" to R.drawable.ik_view_grid,
        "@view_list" to R.drawable.ik_view_list,
        "@sematkan" to R.drawable.ik_sematkan,
        "@hibernasi" to R.drawable.ik_hibernasi,
        "@tutup_otomatis" to R.drawable.ik_tutup_otomatis,
        "@tambah_page" to R.drawable.ik_tambah_page,
        "@urut_hapus" to R.drawable.ik_urut_hapus
    )

    fun has(key: String) = map.containsKey(key)

    fun make(ctx: Context, key: String, emojiSp: Float): View {
        val res = map[key]
        if (res != null) {
            return ImageView(ctx).apply {
                setImageResource(res)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        return TextView(ctx).apply { text = key; textSize = emojiSp; gravity = Gravity.CENTER }
    }
}
