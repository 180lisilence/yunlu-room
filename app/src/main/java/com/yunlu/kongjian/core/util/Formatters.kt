package com.yunlu.kongjian.core.util

import android.content.Context
import android.text.format.Formatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/** Format a file size (bytes) using the system's locale-aware formatter. */
fun formatFileSize(context: Context, bytes: Long): String =
    Formatter.formatFileSize(context, bytes.coerceAtLeast(0L))

/** Format an epoch timestamp; returns "-" for invalid values. */
fun formatDateTime(time: Long): String =
    if (time <= 0L) "-" else dateTimeFormat.format(Date(time))
