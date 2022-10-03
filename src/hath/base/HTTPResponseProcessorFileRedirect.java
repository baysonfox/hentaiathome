/*

Copyright 2008-2022 E-Hentai.org
https://forums.e-hentai.org/
ehentai@gmail.com

This file is part of Hentai@Home.

Hentai@Home is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Hentai@Home is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Hentai@Home.  If not, see <http://www.gnu.org/licenses/>.

*/

package hath.base;

import java.nio.ByteBuffer;

// this class provides a header value like X-Accel-Redirect instead of an actual file

public class HTTPResponseProcessorFileRedirect extends HTTPResponseProcessor {
	private HVFile requestedHVFile;
    private static final ByteBuffer emptyBuffer = ByteBuffer.wrap(new byte[0], 0, 0).asReadOnlyBuffer();

	public HTTPResponseProcessorFileRedirect(HVFile requestedHVFile) {
		this.requestedHVFile = requestedHVFile;
	}

	public int initialize() {
		int responseStatusCode = 200;
        addHeaderField(Settings.getFileRedirectHeader(), Settings.getFileRedirectPath() + requestedHVFile.getLocalFilePath());
		Stats.fileSent();
		return responseStatusCode;
	}

	public String getContentType() {
		return requestedHVFile.getMimeType();
	}

	public int getContentLength() {
		return 0;
	}

	public ByteBuffer getPreparedTCPBuffer() throws Exception {
		return emptyBuffer;
	}
}
