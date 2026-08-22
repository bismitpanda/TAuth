package com.panda.tauth.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import tauth.shared.generated.resources.Res
import tauth.shared.generated.resources.ic_add
import tauth.shared.generated.resources.ic_arrow_back
import tauth.shared.generated.resources.ic_check
import tauth.shared.generated.resources.ic_close
import tauth.shared.generated.resources.ic_content_copy
import tauth.shared.generated.resources.ic_content_paste
import tauth.shared.generated.resources.ic_delete
import tauth.shared.generated.resources.ic_download
import tauth.shared.generated.resources.ic_drag_indicator
import tauth.shared.generated.resources.ic_edit
import tauth.shared.generated.resources.ic_folder_open
import tauth.shared.generated.resources.ic_image
import tauth.shared.generated.resources.ic_keyboard
import tauth.shared.generated.resources.ic_lock
import tauth.shared.generated.resources.ic_more_vert
import tauth.shared.generated.resources.ic_password
import tauth.shared.generated.resources.ic_qr_code_2
import tauth.shared.generated.resources.ic_refresh
import tauth.shared.generated.resources.ic_reorder
import tauth.shared.generated.resources.ic_save
import tauth.shared.generated.resources.ic_schedule
import tauth.shared.generated.resources.ic_search
import tauth.shared.generated.resources.ic_settings
import tauth.shared.generated.resources.ic_sort_by_alpha
import tauth.shared.generated.resources.ic_upload
import tauth.shared.generated.resources.ic_visibility
import tauth.shared.generated.resources.ic_visibility_off
import tauth.shared.generated.resources.ic_warning

val ControlIcon = 18.dp

object TauthIcons {
    val add: Painter @Composable get() = painterResource(Res.drawable.ic_add)
    val back: Painter @Composable get() = painterResource(Res.drawable.ic_arrow_back)
    val check: Painter @Composable get() = painterResource(Res.drawable.ic_check)
    val close: Painter @Composable get() = painterResource(Res.drawable.ic_close)
    val copy: Painter @Composable get() = painterResource(Res.drawable.ic_content_copy)
    val delete: Painter @Composable get() = painterResource(Res.drawable.ic_delete)
    val edit: Painter @Composable get() = painterResource(Res.drawable.ic_edit)
    val export: Painter @Composable get() = painterResource(Res.drawable.ic_download)
    val import: Painter @Composable get() = painterResource(Res.drawable.ic_upload)
    val lock: Painter @Composable get() = painterResource(Res.drawable.ic_lock)
    val more: Painter @Composable get() = painterResource(Res.drawable.ic_more_vert)
    val qr: Painter @Composable get() = painterResource(Res.drawable.ic_qr_code_2)
    val generate: Painter @Composable get() = painterResource(Res.drawable.ic_refresh)
    val reorder: Painter @Composable get() = painterResource(Res.drawable.ic_drag_indicator)
    val reveal: Painter @Composable get() = painterResource(Res.drawable.ic_folder_open)
    val search: Painter @Composable get() = painterResource(Res.drawable.ic_search)
    val settings: Painter @Composable get() = painterResource(Res.drawable.ic_settings)
    val show: Painter @Composable get() = painterResource(Res.drawable.ic_visibility)
    val hide: Painter @Composable get() = painterResource(Res.drawable.ic_visibility_off)
    val warning: Painter @Composable get() = painterResource(Res.drawable.ic_warning)
    val save: Painter @Composable get() = painterResource(Res.drawable.ic_save)
    val paste: Painter @Composable get() = painterResource(Res.drawable.ic_content_paste)
    val image: Painter @Composable get() = painterResource(Res.drawable.ic_image)
    val typed: Painter @Composable get() = painterResource(Res.drawable.ic_keyboard)
    val sortManual: Painter @Composable get() = painterResource(Res.drawable.ic_reorder)
    val sortIssuer: Painter @Composable get() = painterResource(Res.drawable.ic_sort_by_alpha)
    val sortRecent: Painter @Composable get() = painterResource(Res.drawable.ic_schedule)
    val password: Painter @Composable get() = painterResource(Res.drawable.ic_password)
}
