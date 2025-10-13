package com.itsaky.androidide.lsp.java.debug.utils

import com.sun.jdi.BooleanValue
import com.sun.jdi.ByteValue
import com.sun.jdi.CharValue
import com.sun.jdi.DoubleValue
import com.sun.jdi.FloatValue
import com.sun.jdi.IntegerValue
import com.sun.jdi.LongValue
import com.sun.jdi.Mirror
import com.sun.jdi.ShortValue
import com.sun.jdi.StringReference
import com.sun.jdi.VoidValue

internal fun Mirror.mirrorOf(value: Boolean): BooleanValue = virtualMachine().mirrorOf(value)
internal fun Mirror.mirrorOf(value: Byte): ByteValue = virtualMachine().mirrorOf(value)
internal fun Mirror.mirrorOf(value: Char): CharValue = virtualMachine().mirrorOf(value)
internal fun Mirror.mirrorOf(value: Short): ShortValue = virtualMachine().mirrorOf(value)
internal fun Mirror.mirrorOf(value: Int): IntegerValue = virtualMachine().mirrorOf(value)
internal fun Mirror.mirrorOf(value: Long): LongValue = virtualMachine().mirrorOf(value)
internal fun Mirror.mirrorOf(value: Float): FloatValue = virtualMachine().mirrorOf(value)
internal fun Mirror.mirrorOf(value: Double): DoubleValue = virtualMachine().mirrorOf(value)
internal fun Mirror.mirrorOf(value: String): StringReference = virtualMachine().mirrorOf(value)
internal fun Mirror.void(): VoidValue = virtualMachine().mirrorOfVoid()