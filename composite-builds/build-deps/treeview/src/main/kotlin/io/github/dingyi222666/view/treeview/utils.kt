/*
 * TreeView - An TreeView implement in Android with RecyclerView written in kotlin.
 *  https://github.com/dingyi222666/TreeView
 *
 * Copyright (C) 2023-2024. dingyi222666@foxmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.dingyi222666.view.treeview

import android.view.MotionEvent

internal fun generateTranslatedMotionEvent(origin: MotionEvent, dx: Float, dy: Float) =
    MotionEvent.obtain(
        origin.downTime - 1,
        origin.eventTime,
        origin.action,
        origin.pointerCount,
        Array(origin.pointerCount) { index ->
            MotionEvent.PointerProperties().also {
                origin.getPointerProperties(index, it)
            }
        },
        Array(origin.pointerCount) { index ->
            MotionEvent.PointerCoords().also {
                origin.getPointerCoords(index, it)
                it.x += dx
                it.y += dy
            }
        },
        origin.metaState,
        origin.buttonState,
        origin.xPrecision,
        origin.yPrecision,
        origin.deviceId,
        origin.edgeFlags,
        origin.source,
        origin.flags
    )