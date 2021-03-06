/*
 * Copyright 2012 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.webapp.servlet;

import azkaban.project.Project;
import azkaban.server.session.Session;
import azkaban.user.Permission;
import azkaban.user.Role;
import azkaban.user.User;
import azkaban.user.UserManager;
import azkaban.user.UserManagerException;
import azkaban.utils.StringUtils;
import azkaban.utils.WebUtils;
import azkaban.webapp.WebMetrics;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;

/**
 * Abstract Servlet that handles auto login when the session hasn't been
 * verified.
 */
public abstract class LoginAbstractAzkabanServlet extends
    AbstractAzkabanServlet {

  private static final long serialVersionUID = 1L;

  private static final Logger logger = Logger
      .getLogger(LoginAbstractAzkabanServlet.class.getName());
  private static final String SESSION_ID_NAME = "azkaban.browser.session.id";
  private static final int DEFAULT_UPLOAD_DISK_SPOOL_SIZE = 20 * 1024 * 1024;

  private static final HashMap<String, String> contextType =
      new HashMap<>();

  static {
    contextType.put(".js", "application/javascript");
    contextType.put(".css", "text/css");
    contextType.put(".png", "image/png");
    contextType.put(".jpeg", "image/jpeg");
    contextType.put(".gif", "image/gif");
    contextType.put(".jpg", "image/jpeg");
    contextType.put(".eot", "application/vnd.ms-fontobject");
    contextType.put(".svg", "image/svg+xml");
    contextType.put(".ttf", "application/octet-stream");
    contextType.put(".woff", "application/x-font-woff");
  }

  private File webResourceDirectory = null;

  private MultipartParser multipartParser;

  private boolean shouldLogRawUserAgent = false;

  @Override
  public void init(final ServletConfig config) throws ServletException {
    super.init(config);

    this.multipartParser = new MultipartParser(DEFAULT_UPLOAD_DISK_SPOOL_SIZE);

    this.shouldLogRawUserAgent =
        getApplication().getServerProps().getBoolean("accesslog.raw.useragent",
            false);
  }

  public void setResourceDirectory(final File file) {
    this.webResourceDirectory = file;
  }

  @Override
  protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)
      throws ServletException, IOException {

    WebMetrics.INSTANCE.markWebGetCall();
    // Set session id
    final Session session = getSessionFromRequest(req);
    logRequest(req, session);
    if (hasParam(req, "logout")) {
      resp.sendRedirect(req.getContextPath());
      if (session != null) {
        getApplication().getSessionCache()
            .removeSession(session.getSessionId());
      }
      return;
    }

    if (session != null) {
      if (logger.isDebugEnabled()) {
        logger.debug("Found session " + session.getUser());
      }
      if (handleFileGet(req, resp)) {
        return;
      }

      handleGet(req, resp, session);
    } else {
      if (hasParam(req, "ajax")) {
        final HashMap<String, String> retVal = new HashMap<>();
        retVal.put("error", "session");
        this.writeJSON(resp, retVal);
      } else {
        handleLogin(req, resp);
      }
    }
  }

  /**
   * Log out request - the format should be close to Apache access log format
   */
  private void logRequest(final HttpServletRequest req, final Session session) {
    final StringBuilder buf = new StringBuilder();
    buf.append(getRealClientIpAddr(req)).append(" ");
    if (session != null && session.getUser() != null) {
      buf.append(session.getUser().getUserId()).append(" ");
    } else {
      buf.append(" - ").append(" ");
    }

    buf.append("\"");
    buf.append(req.getMethod()).append(" ");
    buf.append(req.getRequestURI()).append(" ");
    if (req.getQueryString() != null) {
      buf.append(req.getQueryString()).append(" ");
    } else {
      buf.append("-").append(" ");
    }
    buf.append(req.getProtocol()).append("\" ");

    final String userAgent = req.getHeader("User-Agent");
    if (this.shouldLogRawUserAgent) {
      buf.append(userAgent);
    } else {
      // simply log a short string to indicate browser or not
      if (StringUtils.isFromBrowser(userAgent)) {
        buf.append("browser");
      } else {
        buf.append("not-browser");
      }
    }

    logger.info(buf.toString());
  }

  private boolean handleFileGet(final HttpServletRequest req, final HttpServletResponse resp)
      throws IOException {
    if (this.webResourceDirectory == null) {
      return false;
    }

    // Check if it's a resource
    final String prefix = req.getContextPath() + req.getServletPath();
    final String path = req.getRequestURI().substring(prefix.length());
    final int index = path.lastIndexOf('.');
    if (index == -1) {
      return false;
    }

    final String extension = path.substring(index);
    if (contextType.containsKey(extension)) {
      final File file = new File(this.webResourceDirectory, path);
      if (!file.exists() || !file.isFile()) {
        return false;
      }

      resp.setContentType(contextType.get(extension));

      final OutputStream output = resp.getOutputStream();
      BufferedInputStream input = null;
      try {
        input = new BufferedInputStream(new FileInputStream(file));
        IOUtils.copy(input, output);
      } finally {
        if (input != null) {
          input.close();
        }
      }
      output.flush();
      return true;
    }

    return false;
  }

  private String getRealClientIpAddr(final HttpServletRequest req) {

    // If some upstream device added an X-Forwarded-For header
    // use it for the client ip
    // This will support scenarios where load balancers or gateways
    // front the Azkaban web server and a changing Ip address invalidates
    // the session
    final HashMap<String, String> headers = new HashMap<>();
    headers.put(WebUtils.X_FORWARDED_FOR_HEADER,
        req.getHeader(WebUtils.X_FORWARDED_FOR_HEADER.toLowerCase()));

    final WebUtils utils = new WebUtils();

    return utils.getRealClientIpAddr(headers, req.getRemoteAddr());
  }

  private Session getSessionFromRequest(final HttpServletRequest req)
      throws ServletException {
    final String remoteIp = getRealClientIpAddr(req);
    final Cookie cookie = getCookieByName(req, SESSION_ID_NAME);
    String sessionId = null;

    if (cookie != null) {
      sessionId = cookie.getValue();
    }

    if (sessionId == null && hasParam(req, "session.id")) {
      sessionId = getParam(req, "session.id");
    }
    return getSessionFromSessionId(sessionId, remoteIp);
  }

  private Session getSessionFromSessionId(final String sessionId, final String remoteIp) {
    if (sessionId == null) {
      return null;
    }

    final Session session = getApplication().getSessionCache().getSession(sessionId);
    // Check if the IP's are equal. If not, we invalidate the sesson.
    if (session == null || !remoteIp.equals(session.getIp())) {
      return null;
    }

    return session;
  }

  private void handleLogin(final HttpServletRequest req, final HttpServletResponse resp)
      throws ServletException, IOException {
    handleLogin(req, resp, null);
  }

  private void handleLogin(final HttpServletRequest req, final HttpServletResponse resp,
      final String errorMsg) throws ServletException, IOException {
    final Page page = newPage(req, resp, "azkaban/webapp/servlet/velocity/login.vm");
    if (errorMsg != null) {
      page.add("errorMsg", errorMsg);
    }

    page.render();
  }

  @Override
  protected void doPost(final HttpServletRequest req, final HttpServletResponse resp)
      throws ServletException, IOException {
    Session session = getSessionFromRequest(req);
    WebMetrics.INSTANCE.markWebPostCall();
    logRequest(req, session);

    // Handle Multipart differently from other post messages
    if (ServletFileUpload.isMultipartContent(req)) {
      final Map<String, Object> params = this.multipartParser.parseMultipart(req);
      if (session == null) {
        // See if the session id is properly set.
        if (params.containsKey("session.id")) {
          final String sessionId = (String) params.get("session.id");
          final String ip = getRealClientIpAddr(req);

          session = getSessionFromSessionId(sessionId, ip);
          if (session != null) {
            handleMultiformPost(req, resp, params, session);
            return;
          }
        }

        // if there's no valid session, see if it's a one time session.
        if (!params.containsKey("username") || !params.containsKey("password")) {
          writeResponse(resp, "Login error. Need username and password");
          return;
        }

        final String username = (String) params.get("username");
        final String password = (String) params.get("password");
        final String ip = getRealClientIpAddr(req);

        try {
          session = createSession(username, password, ip);
        } catch (final UserManagerException e) {
          writeResponse(resp, "Login error: " + e.getMessage());
          return;
        }
      }

      handleMultiformPost(req, resp, params, session);
    } else if (hasParam(req, "action")
        && getParam(req, "action").equals("login")) {
      final HashMap<String, Object> obj = new HashMap<>();
      handleAjaxLoginAction(req, resp, obj);
      this.writeJSON(resp, obj);
    } else if (session == null) {
      if (hasParam(req, "username") && hasParam(req, "password")) {
        // If it's a post command with curl, we create a temporary session
        try {
          session = createSession(req);
        } catch (final UserManagerException e) {
          writeResponse(resp, "Login error: " + e.getMessage());
        }

        handlePost(req, resp, session);
      } else {
        // There are no valid sessions and temporary logins, no we either pass
        // back a message or redirect.
        if (isAjaxCall(req)) {
          final String response =
              createJsonResponse("error", "Invalid Session. Need to re-login",
                  "login", null);
          writeResponse(resp, response);
        } else {
          handleLogin(req, resp, "Enter username and password");
        }
      }
    } else {
      handlePost(req, resp, session);
    }
  }

  private Session createSession(final HttpServletRequest req)
      throws UserManagerException, ServletException {
    final String username = getParam(req, "username");
    final String password = getParam(req, "password");
    final String ip = getRealClientIpAddr(req);

    return createSession(username, password, ip);
  }

  private Session createSession(final String username, final String password, final String ip)
      throws UserManagerException, ServletException {
    final UserManager manager = getApplication().getUserManager();
    final User user = manager.getUser(username, password);

    final String randomUID = UUID.randomUUID().toString();
    final Session session = new Session(randomUID, user, ip);

    return session;
  }

  protected boolean hasPermission(final Project project, final User user,
      final Permission.Type type) {
    final UserManager userManager = getApplication().getUserManager();
    if (project.hasPermission(user, type)) {
      return true;
    }

    for (final String roleName : user.getRoles()) {
      final Role role = userManager.getRole(roleName);
      if (role.getPermission().isPermissionSet(type)
          || role.getPermission().isPermissionSet(Permission.Type.ADMIN)) {
        return true;
      }
    }

    return false;
  }

  protected void handleAjaxLoginAction(final HttpServletRequest req,
      final HttpServletResponse resp, final Map<String, Object> ret)
      throws ServletException {
    if (hasParam(req, "username") && hasParam(req, "password")) {
      Session session = null;
      try {
        session = createSession(req);
      } catch (final UserManagerException e) {
        ret.put("error", "Incorrect Login. " + e.getMessage());
        return;
      }

      final Cookie cookie = new Cookie(SESSION_ID_NAME, session.getSessionId());
      cookie.setPath("/");
      resp.addCookie(cookie);
      getApplication().getSessionCache().addSession(session);
      ret.put("status", "success");
      ret.put("session.id", session.getSessionId());
    } else {
      ret.put("error", "Incorrect Login.");
    }
  }

  protected void writeResponse(final HttpServletResponse resp, final String response)
      throws IOException {
    final Writer writer = resp.getWriter();
    writer.append(response);
    writer.flush();
  }

  protected boolean isAjaxCall(final HttpServletRequest req) throws ServletException {
    final String value = req.getHeader("X-Requested-With");
    if (value != null) {
      logger.info("has X-Requested-With " + value);
      return value.equals("XMLHttpRequest");
    }

    return false;
  }

  /**
   * The get request is handed off to the implementor after the user is logged
   * in.
   */
  protected abstract void handleGet(HttpServletRequest req,
      HttpServletResponse resp, Session session) throws ServletException,
      IOException;

  /**
   * The post request is handed off to the implementor after the user is logged
   * in.
   */
  protected abstract void handlePost(HttpServletRequest req,
      HttpServletResponse resp, Session session) throws ServletException,
      IOException;

  /**
   * The post request is handed off to the implementor after the user is logged
   * in.
   */
  protected void handleMultiformPost(final HttpServletRequest req,
      final HttpServletResponse resp, final Map<String, Object> multipart, final Session session)
      throws ServletException, IOException {
  }
}
