/*-
 * #%L
 * quickbuf-runtime
 * %%
 * Copyright (C) 2019 HEBI Robotics
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package com.diffbot.primibuf.runtime;

import java.nio.charset.Charset;

/**
 * Utility methods for working with protobuf messages
 *
 * @author Florian Enner
 * @since 09 Aug 2019
 */
public class ProtoUtil {

    static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    static class Charsets {
        static final Charset UTF_8 = Charset.forName("UTF-8");
        static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
        static final Charset ASCII = Charset.forName("US-ASCII");
    }
}
