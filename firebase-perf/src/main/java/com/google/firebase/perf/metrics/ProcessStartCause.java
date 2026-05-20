// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
//
// You may obtain a copy of the License at
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.perf.metrics;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Causal signal for "was this process forked because the user launched the app?".
 *
 * <p>Captured once at the earliest point we have access to a {@link Context} in
 * {@link AppStartTrace}, before the existing runnable-vs-{@code onActivityCreated} timing
 * heuristic runs. The value is emitted as a custom attribute on the
 * {@code _experiment_app_start_ttid} experiment trace in Phase 1 so we can compare it
 * against the existing decision in production telemetry. Phase 2 (per
 * {@code PLAN_APPSTART_CAUSAL_SIGNAL.md}) will switch the actual decision to consult
 * this value first.
 *
 * <p>OS-version strategy:
 * <ul>
 *   <li>API 35+: {@code ActivityManager.getHistoricalProcessStartReasons(int)} returns
 *       a list of {@code ApplicationStartInfo}, whose {@code getReason()} is the OS's
 *       authoritative answer (LAUNCHER / BROADCAST / CONTENT_PROVIDER / SERVICE / …).
 *       Accessed reflectively because the repo's {@code compileSdkVersion} is currently
 *       34 and the API 35 types are not on the compile classpath.
 *   <li>API 34: {@link ActivityManager#getMyMemoryState(ActivityManager.RunningAppProcessInfo)}
 *       reports the current importance. {@code IMPORTANCE_FOREGROUND} at first capture
 *       is a reliable indicator that the process was forked for an activity launch.
 *   <li>API &lt;34: returns {@link Cause#UNKNOWN}. The pre-API-34 timing-window logic is
 *       known to be correct on those versions, so no causal signal is needed.
 * </ul>
 *
 * <p>Empirical validation of these claims on emulator-tier API 33/34/35/36: see
 * {@code ~/AndroidStudioProjects/api34-appstart-cause/RESULTS.md} (out-of-tree).
 *
 * @hide
 */
final class ProcessStartCause {

  /** The classification this signal makes. */
  enum Cause {
    /** Strong evidence the process was forked to satisfy an activity launch. */
    FOREGROUND,
    /** Strong evidence the process was forked for a non-activity reason. */
    BACKGROUND,
    /** Signal couldn't decide — caller should fall back to the existing heuristic. */
    UNKNOWN
  }

  // ApplicationStartInfo.START_REASON_* constants (API 35+). Duplicated here as int
  // literals so this file compiles under compileSdkVersion=34. Values are stable in
  // public OS source; if Google ever renumbers, this code emits the "UNKNOWN_<n>" label
  // and the reflective reasonValueOf call still works.
  private static final int START_REASON_ALARM = 0;
  private static final int START_REASON_BACKUP = 1;
  private static final int START_REASON_BOOT_COMPLETE = 2;
  private static final int START_REASON_BROADCAST = 3;
  private static final int START_REASON_CONTENT_PROVIDER = 4;
  private static final int START_REASON_JOB = 5;
  private static final int START_REASON_LAUNCHER = 6;
  private static final int START_REASON_LAUNCHER_RECENTS = 7;
  private static final int START_REASON_OTHER = 8;
  private static final int START_REASON_PUSH = 9;
  private static final int START_REASON_SERVICE = 10;
  private static final int START_REASON_START_ACTIVITY = 11;

  private static final int START_TYPE_UNSET = 0;
  private static final int START_TYPE_COLD = 1;
  private static final int START_TYPE_WARM = 2;
  private static final int START_TYPE_HOT = 3;

  /** OS-side classification. Never null. */
  final @NonNull Cause cause;

  /**
   * API 35+ {@code ApplicationStartInfo.getReason()} name (e.g. {@code "LAUNCHER"},
   * {@code "BROADCAST"}), or empty string when not API 35+ or no record was returned.
   */
  final @NonNull String reasonName;

  /**
   * API 35+ {@code ApplicationStartInfo.getStartType()} name ({@code "COLD"},
   * {@code "WARM"}, {@code "HOT"}), or empty string when not API 35+ or no record was
   * returned.
   */
  final @NonNull String startTypeName;

  /**
   * Raw {@link ActivityManager.RunningAppProcessInfo#importance} at capture, or {@code -1}
   * if the value could not be read. Captured on every API for telemetry continuity, but
   * only consulted for classification on API 34.
   */
  final int importance;

  /** {@link Build.VERSION#SDK_INT} at capture. */
  final int apiLevel;

  @VisibleForTesting
  ProcessStartCause(
      @NonNull Cause cause,
      @NonNull String reasonName,
      @NonNull String startTypeName,
      int importance,
      int apiLevel) {
    this.cause = cause;
    this.reasonName = reasonName;
    this.startTypeName = startTypeName;
    this.importance = importance;
    this.apiLevel = apiLevel;
  }

  /**
   * Capture the cause for the current process. Safe to call once at any point after the
   * application has a {@link Context}; callers should invoke as early as possible (e.g.
   * inside {@code AppStartTrace.registerActivityLifecycleCallbacks}) so the OS-set values
   * still reflect the original fork reason rather than transient state mid-init.
   */
  static @NonNull ProcessStartCause capture(@Nullable Context appContext) {
    final int apiLevel = Build.VERSION.SDK_INT;
    if (appContext == null) {
      return new ProcessStartCause(Cause.UNKNOWN, "", "", -1, apiLevel);
    }

    final ActivityManager activityManager =
        (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
    if (activityManager == null) {
      return new ProcessStartCause(Cause.UNKNOWN, "", "", -1, apiLevel);
    }

    final int importance = readImportance();

    // API 35+ path: ApplicationStartInfo is authoritative.
    if (apiLevel >= 35) {
      ApplicationStartInfoView startInfo = readApplicationStartInfo(activityManager);
      if (startInfo != null) {
        return new ProcessStartCause(
            classifyReason(startInfo.reason),
            reasonName(startInfo.reason),
            startTypeName(startInfo.startType),
            importance,
            apiLevel);
      }
      // Fall through if reflective access returned nothing (unexpected on API 35+, but
      // defensive). importance is still useful telemetry.
    }

    // API 34: importance at first capture distinguishes foreground from background starts.
    if (apiLevel == 34) {
      Cause cause =
          importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
              ? Cause.FOREGROUND
              : Cause.UNKNOWN;
      return new ProcessStartCause(cause, "", "", importance, apiLevel);
    }

    // API < 34: existing logic is correct, so we don't try.
    return new ProcessStartCause(Cause.UNKNOWN, "", "", importance, apiLevel);
  }

  private static int readImportance() {
    try {
      ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
      ActivityManager.getMyMemoryState(info);
      return info.importance;
    } catch (Throwable t) {
      return -1;
    }
  }

  /**
   * Reflectively call {@code ActivityManager.getHistoricalProcessStartReasons(int)} and
   * pull {@code getReason()} / {@code getStartType()} off the first returned
   * {@code ApplicationStartInfo}. Returns {@code null} if anything goes wrong; callers
   * must handle the null case.
   */
  @Nullable
  private static ApplicationStartInfoView readApplicationStartInfo(
      @NonNull ActivityManager activityManager) {
    if (Build.VERSION.SDK_INT < 35) {
      return null;
    }
    try {
      Method getHistoricalProcessStartReasons =
          ActivityManager.class.getMethod("getHistoricalProcessStartReasons", int.class);
      Object result = getHistoricalProcessStartReasons.invoke(activityManager, 1);
      if (!(result instanceof List)) {
        return null;
      }
      List<?> list = (List<?>) result;
      if (list.isEmpty()) {
        return null;
      }
      Object startInfo = list.get(0);
      if (startInfo == null) {
        return null;
      }
      Class<?> startInfoClass = startInfo.getClass();
      Method getReason = startInfoClass.getMethod("getReason");
      Method getStartType = startInfoClass.getMethod("getStartType");
      Object reason = getReason.invoke(startInfo);
      Object startType = getStartType.invoke(startInfo);
      if (!(reason instanceof Integer) || !(startType instanceof Integer)) {
        return null;
      }
      return new ApplicationStartInfoView((Integer) reason, (Integer) startType);
    } catch (Throwable t) {
      return null;
    }
  }

  /** Map {@code ApplicationStartInfo.getReason()} → {@link Cause}. */
  private static @NonNull Cause classifyReason(int reason) {
    switch (reason) {
      case START_REASON_LAUNCHER:
      case START_REASON_LAUNCHER_RECENTS:
      case START_REASON_START_ACTIVITY:
        return Cause.FOREGROUND;
      case START_REASON_ALARM:
      case START_REASON_BACKUP:
      case START_REASON_BOOT_COMPLETE:
      case START_REASON_BROADCAST:
      case START_REASON_CONTENT_PROVIDER:
      case START_REASON_JOB:
      case START_REASON_PUSH:
      case START_REASON_SERVICE:
        return Cause.BACKGROUND;
      default:
        // START_REASON_OTHER and any future values: defer to fallback.
        return Cause.UNKNOWN;
    }
  }

  private static @NonNull String reasonName(int reason) {
    switch (reason) {
      case START_REASON_ALARM:
        return "ALARM";
      case START_REASON_BACKUP:
        return "BACKUP";
      case START_REASON_BOOT_COMPLETE:
        return "BOOT_COMPLETE";
      case START_REASON_BROADCAST:
        return "BROADCAST";
      case START_REASON_CONTENT_PROVIDER:
        return "CONTENT_PROVIDER";
      case START_REASON_JOB:
        return "JOB";
      case START_REASON_LAUNCHER:
        return "LAUNCHER";
      case START_REASON_LAUNCHER_RECENTS:
        return "LAUNCHER_RECENTS";
      case START_REASON_OTHER:
        return "OTHER";
      case START_REASON_PUSH:
        return "PUSH";
      case START_REASON_SERVICE:
        return "SERVICE";
      case START_REASON_START_ACTIVITY:
        return "START_ACTIVITY";
      default:
        return "UNKNOWN_" + reason;
    }
  }

  private static @NonNull String startTypeName(int startType) {
    switch (startType) {
      case START_TYPE_COLD:
        return "COLD";
      case START_TYPE_WARM:
        return "WARM";
      case START_TYPE_HOT:
        return "HOT";
      case START_TYPE_UNSET:
        return "UNSET";
      default:
        return "UNKNOWN_" + startType;
    }
  }

  /**
   * Tiny holder so the API 35+ extraction can be done in one place behind reflection
   * without leaking API-35-only types through the helper's signature.
   */
  @VisibleForTesting
  static final class ApplicationStartInfoView {
    final int reason;
    final int startType;

    ApplicationStartInfoView(int reason, int startType) {
      this.reason = reason;
      this.startType = startType;
    }
  }
}
