package dfast.app.ui.more.settings

import dfast.app.R

/**
 * Where each preference row lives in the redesigned settings. This is only a layout table: every
 * preference key and its stored value are unchanged, rows are pulled out of the original XML files
 * by key and shown in question groups on these pages.
 */
object SettingsPages {
    class Group(val titleRes: Int, val resName: String, val keys: List<String>)
    class Page(val id: String, val titleRes: Int, val navId: Int, val groups: List<Group>) {
        val keys: List<String> get() = groups.flatMap { it.keys }
    }

    val sourceXmls = listOf(
        R.xml.general_preferences,
        R.xml.folders_preference,
        R.xml.downloading_preferences,
        R.xml.processing_preferences,
        R.xml.advanced_preferences
    )

    val pages: List<Page> = listOf(
        Page("playback", R.string.o_pg_playback, R.id.settingsPage_playback, listOf(
            Group(R.string.o_g_bgplay, "o_g_bgplay", listOf("bg_autoplay_limit", "bubble_overlay", "display_over_apps")),
        )),
        Page("downloading", R.string.downloads, R.id.settingsPage_downloading, listOf(
            Group(R.string.o_g_tap, "o_g_tap", listOf("incognito", "download_card", "quick_download", "prevent_duplicate_downloads")),
            Group(R.string.o_g_when, "o_g_when", listOf("use_scheduler", "schedule_start", "schedule_end", "ignore_battery")),
            Group(R.string.o_g_what, "o_g_what", listOf("preferred_download_type", "remember_download_type", "last_used_download_type", "audio_bitrate", "audio_format", "video_format", "video_quality")),
            Group(R.string.o_g_with, "o_g_with", listOf("use_sponsorblock", "sponsorblock_filters", "sponsorblock_url", "embed_metadata", "embed_thumbnail", "crop_thumbnail", "embed_subtitles", "write_subtitles", "subs_lang", "add_chapters")),
        )),
        Page("files", R.string.o_pg_files, R.id.settingsPage_files, listOf(
            Group(R.string.o_g_storage, "o_g_storage", listOf("cleanup_leftover_downloads", "check_available_storage", "temporary_files")),
            Group(R.string.o_g_saveto, "o_g_saveto", listOf("music_path", "video_path", "access_all_files")),
            Group(R.string.advanced, "advanced", listOf("command_path", "cache_path", "cache_downloads", "no_part", "keep_cache")),
            Group(R.string.o_g_names, "o_g_names", listOf("file_name_template", "file_name_template_audio", "save_subdirectory", "trim_filenames", "restrict_filenames")),
        )),
        Page("appearance", R.string.appearance, R.id.settingsPage_appearance, listOf(
            Group(R.string.language, "language", listOf("app_language", "locale")),
            Group(R.string.o_g_look, "o_g_look", listOf("dfast_theme", "dfast_icon", "orbit_palette", "theme_accent", "high_contrast")),
            Group(R.string.o_g_cards, "o_g_cards", listOf("modify_download_card", "show_count_downloads")),
            Group(R.string.o_g_player, "o_g_player", listOf("audio_ring_style", "audio_ring_intensity", "audio_glass_flow", "audio_bars", "audio_ring_saver")),
        )),
        Page("home", R.string.o_pg_home, R.id.settingsPage_home, listOf(
            Group(R.string.o_g_yt, "o_g_yt", listOf("recommendations_home", "custom_home_recommendation_url")),
            Group(R.string.o_g_start, "o_g_start", listOf("history_quick_action", "start_destination", "navigation_bar", "label_visibility")),
            Group(R.string.search, "search", listOf("search_engine", "check_clipboard_home", "search_suggestions")),
            Group(R.string.o_g_gest, "o_g_gest", listOf("swipe_gesture", "swipe_gestures_download_card", "show_quick_download_share")),
        )),
        Page("formats", R.string.o_pg_formats, R.id.settingsPage_formats, listOf(
            Group(R.string.general, "general", listOf("mtime", "write_description", "force_keyframes")),
            Group(R.string.audio, "audio", listOf("embed_lyrics", "save_lrc", "playlist_as_album", "audio_language", "audio_codec", "prefer_container_over_codec_audio", "prefer_drc_audio")),
            Group(R.string.video, "video", listOf("write_auto_subtitles", "no_keep_subs", "sub_format", "write_thumbnail", "video_embed_thumbnail", "thumbnail_format", "recode_video", "compatible_video", "video_codec", "internal_plugin_ffmpeg_preset", "remove_audio", "also_download_audio")),
        )),
        Page("engine", R.string.o_pg_engine, R.id.settingsPage_engine, listOf(
            Group(R.string.o_g_yt, "o_g_yt", listOf("yt_player_client", "generate_po_tokens", "use_app_language_for_metadata", "youtube_other_extractor_args", "api_key", "youtube_data_fetching_extractor", "formats_source", "use_itemurl_instead_playlisturl")),
            Group(R.string.o_g_cmds, "o_g_cmds", listOf("enable_data_fetching_extra_commands", "show_terminal", "write_ytdlp_terminal", "use_extra_commands")),
            Group(R.string.o_g_fmtsel, "o_g_fmtsel", listOf("use_format_sorting", "format_importance_video", "format_importance_audio", "format_id_audio", "format_id", "preferred_format_size", "preferred_format_size_audio")),
            Group(R.string.o_g_behavior, "o_g_behavior", listOf("disable_write_info_json", "no_flat_playlist", "use_original_url_playlist", "download_archive_path", "log_downloads", "use_alarm_for_scheduling")),
        )),
        Page("network", R.string.o_pg_data, R.id.settingsPage_network, listOf(
            Group(R.string.o_g_savedata, "o_g_savedata", listOf("metered_networks", "hide_thumbnails", "limit_rate", "concurrent_downloads")),
            Group(R.string.o_g_conn, "o_g_conn", listOf("no_check_certificates", "use_header", "force_ipv4", "use_cookies", "proxy", "useragent_header")),
            Group(R.string.o_g_speed, "o_g_speed", listOf("download_delay", "concurrent_fragments", "buffer_size", "socket_timeout", "aria2", "retries", "fragment_retries")),
        )),
    )

    fun byId(id: String): Page? = pages.firstOrNull { it.id == id }

    fun pageForKey(key: String?): Page? = if (key == null) null else pages.firstOrNull { key in it.keys }

    fun groupForKey(key: String?): Group? = if (key == null) null else pages.firstNotNullOfOrNull { p -> p.groups.firstOrNull { key in it.keys } }
}
