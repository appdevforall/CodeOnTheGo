/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.appdevforall.codeonthego.tooling.events.work

import org.appdevforall.codeonthego.tooling.events.FinishEvent

/** @author Akash Yadav */
class WorkItemFinishEvent(
  displayName: String,
  eventTime: Long,
  operationDescriptor: WorkItemOperationDescriptor,
  override val result: WorkItemOperationResult
) : WorkItemProgressEvent(displayName, eventTime, operationDescriptor), FinishEvent
