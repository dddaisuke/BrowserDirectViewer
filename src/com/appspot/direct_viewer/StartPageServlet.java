/*
 * Copyright (c) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.appspot.direct_viewer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.appspot.direct_viewer.model.State;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;

/**
 * Servlet to check that the current user is authorized and to serve the start
 * page for DrEdit.
 * 
 * @author vicfryzel@google.com (Vic Fryzel)
 * @author nivco@google.com (Nicolas Garnier)
 * @author jbd@google.com (Burcu Dogan)
 */
@SuppressWarnings("serial")
public class StartPageServlet extends AbstractServlet {
	private static final Logger logger = Logger.getLogger(StartPageServlet.class.getName());

	/**
	 * Ensure that the user is authorized, and setup the required values for
	 * index.jsp.
	 */
	@Override
	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException,
			ServletException {
		Enumeration<String> names = req.getParameterNames();
		for (String name = null; names.hasMoreElements(); name = names.nextElement()) {
			logger.info(req.getParameter(name));
		}
		String stateParam = req.getParameter("state");
		if (stateParam != null) {
			req.getSession().setAttribute(KEY_STATE, req.getParameter("state"));
		}

		String code = req.getParameter("code");
		if (code != null) {
			// handle OAuth2 callback
			handleCallbackIfRequired(req, resp);
		} else if (getCredential(req, resp) == null) {
			// Making sure that we have user credentials
			loginIfRequired(req, resp);
		} else {
			// Deserialize the state in order to specify some values to the
			// DrEdit
			// JavaScript client below.
			mainProcess(req, resp);
		}
	}

	private void mainProcess(HttpServletRequest req, HttpServletResponse resp) throws IOException,
			ServletException {

		String fileId = req.getParameter("fid");
		if (fileId != null) {
			displayImageDirect(req, resp, fileId);
			return;
		}

		String stateParam = req.getParameter("state");
		if (stateParam == null) {
			HttpSession session = req.getSession();
			stateParam = (String) session.getAttribute(KEY_STATE);
			session.removeAttribute(KEY_STATE);
		}
		if (stateParam == null) {
			req.getRequestDispatcher("/public/index.html").forward(req, resp);
			return;
		}

		redirectDisplayPage(resp, stateParam);
	}

	private void redirectDisplayPage(HttpServletResponse resp, String stateParam)
			throws IOException, ServletException {
		State state = new State(stateParam);
		if (state.ids != null && state.ids.size() > 0) {
			String fileId = null;
			for (String id : state.ids) {
				fileId = id;
			}
			resp.sendRedirect("/?fid=" + fileId);

			return;
		} else if (state.folderId != null) {
			resp.sendRedirect("/#/create/" + state.folderId);
			return;
		}
	}

	private void displayImageDirect(HttpServletRequest req, HttpServletResponse resp, String fileId)
			throws IOException {
		Drive service = getDriveService(getCredential(req, resp));

		if (fileId == null) {
			sendError(resp, 400, "The `file_id` URI parameter must be specified.");
			return;
		}

		File file = null;
		try {
			file = service.files().get(fileId).execute();
		} catch (GoogleJsonResponseException e) {
			if (e.getStatusCode() == 401) {
				// The user has revoked our token or it is otherwise
				// bad.
				// Delete the local copy so that their next page load
				// will recover.
				deleteCredential(req, resp);
				sendGoogleJsonResponseError(resp, e);
				return;
			}
		}

		if (file == null) {
			sendError(resp, 404, "File not found");
			return;
		}

		InputStream is = null;
		try {
			is = downloadFileContent(service, file);
			resp.setContentType(file.getMimeType());

			OutputStream out = resp.getOutputStream();
			byte[] data = new byte[1024];
			while (true) {
				int len = is.read(data);
				if (len < 0) {
					break;
				}
				out.write(data, 0, len);
			}
			out.flush();
		} finally {
			if (is != null) {
				is.close();
			}
		}
	}

	private InputStream downloadFileContent(Drive service, File file) throws IOException {
		GenericUrl url = new GenericUrl(file.getDownloadUrl());
		HttpResponse response = service.getRequestFactory().buildGetRequest(url).execute();
		return response.getContent();
	}
}
