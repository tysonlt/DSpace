/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.authenticate;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.StringTokenizer;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.authenticate.factory.AuthenticateServiceFactory;
import org.dspace.authenticate.service.AuthenticationService;
import org.dspace.authorize.AuthorizeException;
import org.dspace.core.Context;
import org.dspace.core.LogHelper;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.GroupService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;

/**
 * Implicit authentication method that gets credentials from the X.509 client
 * certificate supplied by the HTTPS client when connecting to this server. The
 * email address in that certificate is taken as the authenticated user name
 * with no further checking, so be sure your HTTP server (e.g. Tomcat) is
 * configured correctly to accept only client certificates it can validate.
 * <p>
 * See the <code>AuthenticationMethod</code> interface for more details.
 * <p>
 * <b>Configuration:</b>
 *
 * <pre>
 *   x509.keystore.path =
 * <em>
 * path to Java keystore file
 * </em>
 *   keystore.password =
 * <em>
 * password to access the keystore
 * </em>
 *   ca.cert =
 * <em>
 * path to certificate file for CA whose client certs to accept.
 * </em>
 *   autoregister =
 * <em>
 * &quot;true&quot; if E-Person is created automatically for unknown new users.
 * </em>
 *   groups =
 * <em>
 * comma-delimited list of special groups to add user to if authenticated.
 * </em>
 *   emaildomain =
 * <em>
 * email address domain (after the 'at' symbol) to match before allowing
 * membership in special groups.
 * </em>
 * </pre>
 *
 * Only one of the "<code>keystore.path</code>" or "<code>ca.cert</code>"
 * options is required. If you supply a keystore, then all of the "trusted"
 * certificates in the keystore represent CAs whose client certificates will be
 * accepted. The <code>ca.cert</code> option only allows a single CA to be
 * named.
 * <p>
 * You can configure <em>both</em> a keystore and a CA cert, and both will be
 * used.
 * <p>
 * The <code>autoregister</code> configuration parameter determines what the
 * <code>canSelfRegister()</code> method returns. It also allows an EPerson
 * record to be created automatically when the presented certificate is
 * acceptable but there is no corresponding EPerson.
 *
 * @author Larry Stone
 * @version $Revision$
 */
public class X509Authentication implements AuthenticationMethod {

    /**
     * log4j category
     */
    private static Logger log = org.apache.logging.log4j.LogManager.getLogger(X509Authentication.class);

    /**
     * public key of CA to check client certs against.
     */
    private static PublicKey caPublicKey = null;

    /**
     * key store for CA certs if we use that
     */
    private static KeyStore caCertKeyStore = null;

    private static String loginPageTitle = null;

    private static String loginPageURL = null;

    protected AuthenticationService authenticationService = AuthenticateServiceFactory.getInstance()
                                                                                      .getAuthenticationService();
    protected EPersonService ePersonService = EPersonServiceFactory.getInstance().getEPersonService();
    protected GroupService groupService = EPersonServiceFactory.getInstance().getGroupService();
    protected ConfigurationService configurationService =
        DSpaceServicesFactory.getInstance().getConfigurationService();


    /**
     * Initialization: Set caPublicKey and/or keystore. This loads the
     * information needed to check if a client cert presented is valid and
     * acceptable.
     */
    static {
        ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();
        /*
         * allow identification of alternative entry points for certificate
         * authentication when selected by the user rather than implicitly.
         */
        loginPageTitle = configurationService
            .getProperty("authentication-x509.chooser.title.key");
        loginPageURL = configurationService
            .getProperty("authentication-x509.chooser.uri");

        String keystorePath = configurationService
            .getProperty("authentication-x509.keystore.path");
        String keystorePassword = configurationService
            .getProperty("authentication-x509.keystore.password");
        String caCertPath = configurationService
            .getProperty("authentication-x509.ca.cert");

        // First look for keystore full of trusted certs.
        if (keystorePath != null) {
            FileInputStream fis = null;
            if (keystorePassword == null) {
                keystorePassword = "";
            }
            try {
                KeyStore ks = KeyStore.getInstance("JKS");
                fis = new FileInputStream(keystorePath);
                ks.load(fis, keystorePassword.toCharArray());
                caCertKeyStore = ks;
            } catch (IOException e) {
                log
                    .error("X509Authentication: Failed to load CA keystore, file="
                               + keystorePath + ", error=" + e.toString());
            } catch (GeneralSecurityException e) {
                log
                    .error("X509Authentication: Failed to extract CA keystore, file="
                               + keystorePath + ", error=" + e.toString());
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (IOException ioe) {
                        // ignore
                    }
                }
            }
        }

        // Second, try getting public key out of CA cert, if that's configured.
        if (caCertPath != null) {
            InputStream is = null;
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(caCertPath);
                is = new BufferedInputStream(fis);
                X509Certificate cert = (X509Certificate) CertificateFactory
                    .getInstance("X.509").generateCertificate(is);
                if (cert != null) {
                    caPublicKey = cert.getPublicKey();
                }
            } catch (IOException e) {
                log.error("X509Authentication: Failed to load CA cert, file="
                              + caCertPath + ", error=" + e.toString());
            } catch (CertificateException e) {
                log
                    .error("X509Authentication: Failed to extract CA cert, file="
                               + caCertPath + ", error=" + e.toString());
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException ioe) {
                        // ignore
                    }
                }

                if (fis != null) {
                    try {
                        fis.close();
                    } catch (IOException ioe) {
                        // ignore
                    }
                }
            }
        }
    }

    /**
     * Return the email address from <code>certificate</code>, or null if an
     * email address cannot be found in the certificate.
     * <p>
     * Note that the certificate parsing has only been tested with certificates
     * granted by the MIT Certification Authority, and may not work elsewhere.
     *
     * @param certificate -
     *                    An X509 certificate object
     * @return - The email address found in certificate, or null if an email
     * address cannot be found in the certificate.
     */
    private static String getEmail(X509Certificate certificate)
        throws SQLException {
        Principal principal = certificate.getSubjectDN();

        if (principal == null) {
            return null;
        }

        String dn = principal.getName();
        if (dn == null) {
            return null;
        }

        StringTokenizer tokenizer = new StringTokenizer(dn, ",");
        String token = null;
        while (tokenizer.hasMoreTokens()) {
            int len = "emailaddress=".length();

            token = (String) tokenizer.nextToken();

            if (token.toLowerCase().startsWith("emailaddress=")) {
                // Make sure the token actually contains something
                if (token.length() <= len) {
                    return null;
                }

                return token.substring(len).toLowerCase();
            }
        }

        return null;
    }

    /**
     * Verify CERTIFICATE against KEY. Return true if and only if CERTIFICATE is
     * valid and can be verified against KEY.
     *
     * @param context     The current DSpace context
     * @param certificate -
     *                    An X509 certificate object
     * @return - True if CERTIFICATE is valid and can be verified against KEY,
     * false otherwise.
     */
    private static boolean isValid(Context context, X509Certificate certificate) {
        if (certificate == null) {
            return false;
        }

        // This checks that current time is within cert's validity window:
        try {
            certificate.checkValidity();
        } catch (CertificateException e) {
            log.info(LogHelper.getHeader(context, "authentication",
                                          "X.509 Certificate is EXPIRED or PREMATURE: "
                                              + e.toString()));
            return false;
        }

        // Try CA public key, if available.
        if (caPublicKey != null) {
            try {
                certificate.verify(caPublicKey);
                return true;
            } catch (GeneralSecurityException e) {
                log.info(LogHelper.getHeader(context, "authentication",
                                              "X.509 Certificate FAILED SIGNATURE check: "
                                                  + e.toString()));
            }
        }

        // Try it with keystore, if available.
        if (caCertKeyStore != null) {
            try {
                Enumeration ke = caCertKeyStore.aliases();

                while (ke.hasMoreElements()) {
                    String alias = (String) ke.nextElement();
                    if (caCertKeyStore.isCertificateEntry(alias)) {
                        Certificate ca = caCertKeyStore.getCertificate(alias);
                        try {
                            certificate.verify(ca.getPublicKey());
                            return true;
                        } catch (CertificateException ce) {
                            // ignore
                        }
                    }
                }
                log
                    .info(LogHelper
                              .getHeader(context, "authentication",
                                         "Keystore method FAILED SIGNATURE check on client cert."));
            } catch (GeneralSecurityException e) {
                log.info(LogHelper.getHeader(context, "authentication",
                                              "X.509 Certificate FAILED SIGNATURE check: "
                                                  + e.toString()));
            }

        }
        return false;
    }

    /**
     * Predicate, can new user automatically create EPerson. Checks
     * configuration value. You'll probably want this to be true to take
     * advantage of a Web certificate infrastructure with many more users than
     * are already known by DSpace.
     *
     * @throws SQLException if database error
     */
    @Override
    public boolean canSelfRegister(Context context, HttpServletRequest request,
                                   String username) throws SQLException {
        return configurationService
            .getBooleanProperty("authentication-x509.autoregister");
    }

    /**
     * Nothing extra to initialize.
     *
     * @throws SQLException if database error
     */
    @Override
    public void initEPerson(Context context, HttpServletRequest request,
                            EPerson eperson) throws SQLException {
    }

    /**
     * We don't use EPerson password so there is no reason to change it.
     *
     * @throws SQLException if database error
     */
    @Override
    public boolean allowSetPassword(Context context,
                                    HttpServletRequest request, String username) throws SQLException {
        return false;
    }

    /**
     * Returns true, this is an implicit method.
     */
    @Override
    public boolean isImplicit() {
        return true;
    }

    /**
     * Returns a list of group names that the user should be added to upon
     * successful authentication, configured in dspace.cfg.
     *
     * @return List<String> of special groups configured for this authenticator
     */
    private List<String> getX509Groups() {
        List<String> groupNames = new ArrayList<String>();

        String[] groups = configurationService
            .getArrayProperty("authentication-x509.groups");

        if (ArrayUtils.isNotEmpty(groups)) {
            for (String group : groups) {
                groupNames.add(group.trim());
            }
        }

        return groupNames;
    }

    /**
     * Checks for configured email domain required to grant special groups
     * membership. If no email domain is configured to verify, special group
     * membership is simply granted.
     *
     * @param request -
     *                The current request object
     * @param email   -
     *                The email address from the x509 certificate
     */
    private void setSpecialGroupsFlag(HttpServletRequest request, String email) {
        String emailDomain = null;
        emailDomain = (String) request
            .getAttribute("authentication.x509.emaildomain");

        HttpSession session = request.getSession(true);

        if (null != emailDomain && !"".equals(emailDomain)) {
            if (email.substring(email.length() - emailDomain.length()).equals(
                emailDomain)) {
                session.setAttribute("x509Auth", Boolean.TRUE);
            }
        } else {
            // No configured email domain to verify. Just flag
            // as authenticated so special groups are granted.
            session.setAttribute("x509Auth", Boolean.TRUE);
        }
    }

    /**
     * Return special groups configured in dspace.cfg for X509 certificate
     * authentication.
     *
     * @param context context
     * @param request object potentially containing the cert
     * @return An int array of group IDs
     * @throws SQLException if database error
     */
    @Override
    public List<Group> getSpecialGroups(Context context, HttpServletRequest request)
        throws SQLException {
        if (request == null) {
            return Collections.EMPTY_LIST;
        }

        Boolean authenticated = false;
        HttpSession session = request.getSession(false);
        authenticated = (Boolean) session.getAttribute("x509Auth");
        authenticated = (null == authenticated) ? false : authenticated;

        if (authenticated) {
            List<String> groupNames = getX509Groups();
            List<Group> groups = new ArrayList<>();

            if (groupNames != null) {
                for (String groupName : groupNames) {
                    if (groupName != null) {
                        Group group = groupService.findByName(context, groupName);
                        if (group != null) {
                            groups.add(group);
                        } else {
                            log.warn(LogHelper.getHeader(context,
                                                          "configuration_error", "unknown_group="
                                                              + groupName));
                        }
                    }
                }
            }

            return groups;
        }

        return Collections.EMPTY_LIST;
    }

    /**
     * X509 certificate authentication. The client certificate is obtained from
     * the <code>ServletRequest</code> object.
     * <ul>
     * <li>If the certificate is valid, and corresponds to an existing EPerson,
     * and the user is allowed to login, return success.</li>
     * <li>If the user is matched but is not allowed to login, it fails.</li>
     * <li>If the certificate is valid, but there is no corresponding EPerson,
     * the <code>"authentication.x509.autoregister"</code> configuration
     * parameter is checked (via <code>canSelfRegister()</code>)
     * <ul>
     * <li>If it's true, a new EPerson record is created for the certificate,
     * and the result is success.</li>
     * <li>If it's false, return that the user was unknown.</li>
     * </ul>
     * </li>
     * </ul>
     *
     * @return One of: SUCCESS, BAD_CREDENTIALS, NO_SUCH_USER, BAD_ARGS
     * @throws SQLException if database error
     */
    @Override
    public int authenticate(Context context, String username, String password,
                            String realm, HttpServletRequest request) throws SQLException {
        // Obtain the certificate from the request, if any
        X509Certificate[] certs = null;
        if (request != null) {
            certs = (X509Certificate[]) request
                .getAttribute("javax.servlet.request.X509Certificate");
        }

        if ((certs == null) || (certs.length == 0)) {
            return BAD_ARGS;
        } else {
            // We have a cert -- check it and get username from it.
            try {
                if (!isValid(context, certs[0])) {
                    log
                        .warn(LogHelper
                                  .getHeader(context, "authenticate",
                                             "type=x509certificate, status=BAD_CREDENTIALS (not valid)"));
                    return BAD_CREDENTIALS;
                }

                // And it's valid - try and get an e-person
                String email = getEmail(certs[0]);
                EPerson eperson = null;
                if (email != null) {
                    eperson = ePersonService.findByEmail(context, email);
                }
                if (eperson == null) {
                    // Cert is valid, but no record.
                    if (email != null
                        && canSelfRegister(context, request, null)) {
                        // Register the new user automatically
                        log.info(LogHelper.getHeader(context, "autoregister",
                                                      "from=x.509, email=" + email));

                        // TEMPORARILY turn off authorisation
                        context.turnOffAuthorisationSystem();
                        eperson = ePersonService.create(context);
                        eperson.setEmail(email);
                        eperson.setCanLogIn(true);
                        authenticationService.initEPerson(context, request,
                                                          eperson);
                        ePersonService.update(context, eperson);
                        context.dispatchEvents();
                        context.restoreAuthSystemState();
                        context.setCurrentUser(eperson);
                        setSpecialGroupsFlag(request, email);
                        return SUCCESS;
                    } else {
                        // No auto-registration for valid certs
                        log
                            .warn(LogHelper
                                      .getHeader(context, "authenticate",
                                                 "type=cert_but_no_record, cannot auto-register"));
                        return NO_SUCH_USER;
                    }
                } else if (!eperson.canLogIn()) { // make sure this is a login account
                    log.warn(LogHelper.getHeader(context, "authenticate",
                                                  "type=x509certificate, email=" + email
                                                      + ", canLogIn=false, rejecting."));
                    return BAD_ARGS;
                } else {
                    log.info(LogHelper.getHeader(context, "login",
                                                  "type=x509certificate"));
                    context.setCurrentUser(eperson);
                    setSpecialGroupsFlag(request, email);
                    return SUCCESS;
                }
            } catch (AuthorizeException ce) {
                log.warn(LogHelper.getHeader(context, "authorize_exception",
                                              ""), ce);
            }

            return BAD_ARGS;
        }
    }

    /**
     * Returns URL of password-login servlet.
     *
     * @param context  DSpace context, will be modified (EPerson set) upon success.
     * @param request  The HTTP request that started this operation, or null if not
     *                 applicable.
     * @param response The HTTP response from the servlet method.
     * @return fully-qualified URL
     */
    @Override
    public String loginPageURL(Context context, HttpServletRequest request,
                               HttpServletResponse response) {
        return loginPageURL;
    }

    @Override
    public String getName() {
        return "x509";
    }
}
