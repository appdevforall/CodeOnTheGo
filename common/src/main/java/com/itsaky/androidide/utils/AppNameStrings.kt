package com.itsaky.androidide.utils

import com.itsaky.androidide.resources.R

/**
 * The strings that take the app name as their first argument (`%1$s`). [getStringWithAppName] fills
 * the name in only for these, so a string that uses `%1$s` for something else never gets the app name.
 * `AppNameInStringsTest` checks this list against the strings themselves.
 */
internal val APP_NAME_STRINGS: Set<Int> =
	setOf(
		R.string.back_to_cogo,
		R.string.debugger_error_debugger_startup_failure,
		R.string.debugger_error_errno_econnrefused,
		R.string.debugger_error_errno_eperm,
		R.string.debugger_error_suggestion_network_restriction,
		R.string.debugger_setup_description_footer,
		R.string.err_foreground_service_denial,
		R.string.feedback_email_subject,
		R.string.ide_setup_complete,
		R.string.idepref_about_summary,
		R.string.idepref_about_title,
		R.string.idepref_devOptions_dumpLogs_summary,
		R.string.idepref_devOptions_enableLogsender_summary,
		R.string.idepref_devOptions_summary,
		R.string.idepref_general_localeSelector_summary,
		R.string.idepref_general_themeSelector_summary,
		R.string.msg_confirm_project_open,
		R.string.msg_deeplink_setup_incomplete,
		R.string.msg_first_build,
		R.string.msg_help_hint,
		R.string.msg_ide_crashed,
		R.string.msg_long_press_help,
		R.string.msg_need_help,
		R.string.msg_open_projects,
		R.string.notification_adb_pairing_succeed_text,
		R.string.onboarding_subtitle_permissions,
		R.string.package_name_change_message,
		R.string.permission_desc_accessibility,
		R.string.permission_desc_install_packages,
		R.string.permission_desc_notifications,
		R.string.permission_desc_overlay_window,
		R.string.permission_desc_storage,
		R.string.permission_overlay_restricted_dialog_message,
		R.string.permission_overlay_restricted_settings_hint,
		R.string.privacy_disclosure_message,
		R.string.quick_build_reinstall_return_to_cogo,
		R.string.quick_build_reinstall_tap_again,
		R.string.quick_build_reload_crashed,
		R.string.quick_build_switch_unknown_app_message,
		R.string.title_gradle_service_notification_channel,
	)
