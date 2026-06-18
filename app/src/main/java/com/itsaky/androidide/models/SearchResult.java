/************************************************************************************
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 **************************************************************************************/
package com.itsaky.androidide.models;

import java.io.File;

public class SearchResult extends Range {
  public File file;
  public String line;
  public String match;
  public Float similarity; // Similarity score for vector search results (0.0 to 1.0)

  public SearchResult(Range src, File file, String line, String match) {
    super(src.getStart(), src.getEnd());
    this.file = file;
    this.line = line;
    this.match = match;
    this.similarity = null; // null for keyword search results
  }

  public SearchResult(Range src, File file, String line, String match, float similarity) {
    super(src.getStart(), src.getEnd());
    this.file = file;
    this.line = line;
    this.match = match;
    this.similarity = similarity;
  }
}
