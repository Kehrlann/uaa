package org.cloudfoundry.identity.uaa.provider.saml;

import lombok.extern.slf4j.Slf4j;
import net.shibboleth.utilities.java.support.xml.ParserPool;
import org.apache.commons.lang3.StringUtils;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthentication;
import org.cloudfoundry.identity.uaa.authentication.UaaPrincipal;
import org.cloudfoundry.identity.uaa.authentication.event.IdentityProviderAuthenticationSuccessEvent;
import org.cloudfoundry.identity.uaa.authentication.manager.ExternalGroupAuthorizationEvent;
import org.cloudfoundry.identity.uaa.authentication.manager.InvitedUserAuthenticatedEvent;
import org.cloudfoundry.identity.uaa.authentication.manager.NewUserAuthenticatedEvent;
import org.cloudfoundry.identity.uaa.constants.OriginKeys;
import org.cloudfoundry.identity.uaa.provider.IdentityProvider;
import org.cloudfoundry.identity.uaa.provider.IdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.provider.JdbcIdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.provider.SamlIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.user.UaaUser;
import org.cloudfoundry.identity.uaa.user.UaaUserDatabase;
import org.cloudfoundry.identity.uaa.user.UaaUserPrototype;
import org.cloudfoundry.identity.uaa.util.UaaUrlUtils;
import org.cloudfoundry.identity.uaa.web.UaaSavedRequestAwareAuthenticationSuccessHandler;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManager;
import org.opensaml.core.config.ConfigurationService;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistry;
import org.opensaml.core.xml.schema.XSAny;
import org.opensaml.core.xml.schema.XSBase64Binary;
import org.opensaml.core.xml.schema.XSBoolean;
import org.opensaml.core.xml.schema.XSBooleanValue;
import org.opensaml.core.xml.schema.XSDateTime;
import org.opensaml.core.xml.schema.XSInteger;
import org.opensaml.core.xml.schema.XSQName;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.core.xml.schema.XSURI;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.impl.AuthnRequestUnmarshaller;
import org.opensaml.saml.saml2.core.impl.ResponseUnmarshaller;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.saml2.Saml2Exception;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml4AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Optional.of;
import static org.cloudfoundry.identity.uaa.constants.OriginKeys.NotANumber;
import static org.cloudfoundry.identity.uaa.provider.ExternalIdentityProviderDefinition.EMAIL_ATTRIBUTE_NAME;
import static org.cloudfoundry.identity.uaa.provider.ExternalIdentityProviderDefinition.EMAIL_VERIFIED_ATTRIBUTE_NAME;
import static org.cloudfoundry.identity.uaa.provider.ExternalIdentityProviderDefinition.FAMILY_NAME_ATTRIBUTE_NAME;
import static org.cloudfoundry.identity.uaa.provider.ExternalIdentityProviderDefinition.GIVEN_NAME_ATTRIBUTE_NAME;
import static org.cloudfoundry.identity.uaa.provider.ExternalIdentityProviderDefinition.GROUP_ATTRIBUTE_NAME;
import static org.cloudfoundry.identity.uaa.provider.ExternalIdentityProviderDefinition.PHONE_NUMBER_ATTRIBUTE_NAME;
import static org.cloudfoundry.identity.uaa.util.UaaHttpRequestUtils.isAcceptedInvitationAuthentication;
import static org.cloudfoundry.identity.uaa.util.UaaStringUtils.retainAllMatches;

/**
 * SAML Authentication Provider responsible for validating of received SAML messages
 */
@Slf4j
public class SamlLoginAuthenticationProvider implements ApplicationEventPublisherAware, AuthenticationProvider, AuthenticationManager {

    public static final String AUTHENTICATION_CONTEXT_CLASS_REFERENCE = "acr";
    private static final AuthnRequestUnmarshaller authnRequestUnmarshaller;
    private static final ParserPool parserPool;
    private static final ResponseUnmarshaller responseUnmarshaller;

    static {
        XMLObjectProviderRegistry registry = ConfigurationService.get(XMLObjectProviderRegistry.class);
        authnRequestUnmarshaller = (AuthnRequestUnmarshaller) registry.getUnmarshallerFactory()
                .getUnmarshaller(AuthnRequest.DEFAULT_ELEMENT_NAME);

        responseUnmarshaller = (ResponseUnmarshaller) registry.getUnmarshallerFactory()
                .getUnmarshaller(Response.DEFAULT_ELEMENT_NAME);

        parserPool = registry.getParserPool();
    }

    private final IdentityZoneManager identityZoneManager;
    private final UaaUserDatabase userDatabase;
    private final IdentityProviderProvisioning identityProviderProvisioning;
    //    private final ScimGroupExternalMembershipManager externalMembershipManager;
    private ApplicationEventPublisher eventPublisher;

    public SamlLoginAuthenticationProvider(IdentityZoneManager identityZoneManager,
                                           final UaaUserDatabase userDatabase,
                                           final JdbcIdentityProviderProvisioning identityProviderProvisioning) {
        this.identityZoneManager = identityZoneManager;
        this.userDatabase = userDatabase;
        this.identityProviderProvisioning = identityProviderProvisioning;
    }

    /**
     * Attempts to authenticate the passed {@link Authentication} object, returning a
     * fully populated <code>Authentication</code> object (including granted authorities)
     * if successful.
     * <p>
     * An <code>AuthenticationManager</code> must honour the following contract concerning
     * exceptions:
     * <ul>
     * <li>A {@link DisabledException} must be thrown if an account is disabled and the
     * <code>AuthenticationManager</code> can test for this state.</li>
     * <li>A {@link LockedException} must be thrown if an account is locked and the
     * <code>AuthenticationManager</code> can test for account locking.</li>
     * <li>A {@link BadCredentialsException} must be thrown if incorrect credentials are
     * presented. Whilst the above exceptions are optional, an
     * <code>AuthenticationManager</code> must <B>always</B> test credentials.</li>
     * </ul>
     * Exceptions should be tested for and if applicable thrown in the order expressed
     * above (i.e. if an account is disabled or locked, the authentication request is
     * immediately rejected and the credentials testing process is not performed). This
     * prevents credentials being tested against disabled or locked accounts.
     *
     * @param authentication the authentication request object
     * @return a fully authenticated object including credentials. May return
     * <code>null</code> if the <code>AuthenticationProvider</code> is unable to support
     * authentication of the passed <code>Authentication</code> object. In such a case,
     * the next <code>AuthenticationProvider</code> that supports the presented
     * <code>Authentication</code> class will be tried.
     * @throws AuthenticationException if authentication fails.
     *                                 <p>
     *                                                                                                                                                                                                                                                                                                 TODO: Move below into configuration of
     * @see OpenSaml4AuthenticationProvider
     * https://docs.spring.io/spring-security/reference/5.8/migration/servlet/saml2.html#_use_opensaml_4
     */
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {

        if (!supports(authentication.getClass())) {
            throw new IllegalArgumentException("Only SAMLAuthenticationToken is supported, " + authentication.getClass() + " was attempted");
        }

        Saml2AuthenticationToken authenticationToken = (Saml2AuthenticationToken) authentication;
        String serializedResponse = authenticationToken.getSaml2Response();
        Response response = parseResponse(serializedResponse);
        List<Assertion> assertions = response.getAssertions();

        for (Assertion assertion : assertions) {
            log.debug("Assertion: " + assertion);
        }

        IdentityZone zone = identityZoneManager.getCurrentIdentityZone();
        log.debug(String.format("Initiating SAML authentication in zone '%s' domain '%s'", zone.getId(), zone.getSubdomain()));
        RelyingPartyRegistration relyingPartyRegistration = authenticationToken.getRelyingPartyRegistration();
        AbstractSaml2AuthenticationRequest authenticationRequest = authenticationToken.getAuthenticationRequest();

        String relayState;
        if (authenticationRequest != null) {
            relayState = authenticationRequest.getRelayState();
        }

        String subjectName = assertions.get(0).getSubject().getNameID().getValue();
        UaaPrincipal initialPrincipal = new UaaPrincipal(NotANumber, subjectName, authenticationToken.getName(),
                relyingPartyRegistration.getRegistrationId(), authenticationToken.getName(), zone.getId());
        log.debug("Mapped SAML authentication to IDP with origin '{}' and username '{}'",
                relyingPartyRegistration.getRegistrationId(), initialPrincipal.getName());

        List<? extends GrantedAuthority> samlAuthorities = List.copyOf(authenticationToken.getAuthorities());

        LinkedMultiValueMap<String, String> customAttributes = new LinkedMultiValueMap<>();
//        for (Map.Entry<String, List<String>> entry : userAttributes.entrySet()) {
//            if (entry.getKey().startsWith(USER_ATTRIBUTE_PREFIX)) {
//                customAttributes.put(entry.getKey().substring(USER_ATTRIBUTE_PREFIX.length()), entry.getValue());
//            }
//        }

        Set<String> externalGroups = Set.of();
        boolean authenticated = true;
        long authenticatedTime = System.currentTimeMillis();
        long expiresAt = -1;

        UaaAuthentication initialUaaAuthentication = new UaaAuthentication(initialPrincipal,
                authenticationToken.getCredentials(), samlAuthorities, externalGroups, customAttributes, null,
                authenticated, authenticatedTime,
                expiresAt);

        String alias = relyingPartyRegistration.getRegistrationId();
//        String relayState = context.getRelayState();
        boolean addNew;
        IdentityProvider<SamlIdentityProviderDefinition> idp;
        SamlIdentityProviderDefinition samlConfig;
        try {
            idp = identityProviderProvisioning.retrieveByOrigin(alias, identityZoneManager.getCurrentIdentityZoneId());
            samlConfig = idp.getConfig();
            addNew = samlConfig.isAddShadowUserOnLogin();
            if (!idp.isActive()) {
                throw new ProviderNotFoundException("Identity Provider has been disabled by administrator for alias:" + alias);
            }
        } catch (EmptyResultDataAccessException x) {
            throw new ProviderNotFoundException("No SAML identity provider found in zone for alias:" + alias);
        }
//
        log.debug(
                String.format(
                        "Mapped SAML authentication to IDP with origin '%s' and username '%s'",
                        idp.getOriginKey(),
                        initialPrincipal.getName()
                )
        );

        //Collection<? extends GrantedAuthority> samlAuthorities = retrieveSamlAuthorities(samlConfig, (SAMLCredential) result.getCredentials());
//
//        Collection<? extends GrantedAuthority> authorities =
        // Collection<? extends GrantedAuthority> samlAuthoritinull;
//        SamlIdentityProviderDefinition.ExternalGroupMappingMode groupMappingMode = idp.getConfig().getGroupMappingMode();
//        switch (groupMappingMode) {
//            case EXPLICITLY_MAPPED:
//                authorities = mapAuthorities(idp.getOriginKey(), samlAuthorities);
//                break;
//            case AS_SCOPES:
//                authorities = new LinkedList<>(samlAuthorities);
//                break;
//        }
//
//        Set<String> filteredExternalGroups = filterSamlAuthorities(samlConfig, samlAuthorities);
        initialUaaAuthentication.setAuthenticationMethods(Set.of("ext"));
        MultiValueMap<String, String> userAttributes = retrieveUserAttributes(samlConfig, response);
        List<String> acrValues = userAttributes.get(AUTHENTICATION_CONTEXT_CLASS_REFERENCE);
        if (acrValues != null) {
            initialUaaAuthentication.setAuthContextClassRef(Set.copyOf(acrValues));
        }
//
//        if (samlConfig.getAuthnContext() != null) {
//            if (Collections.disjoint(userAttributes.get(AUTHENTICATION_CONTEXT_CLASS_REFERENCE), samlConfig.getAuthnContext())) {
//                throw new BadCredentialsException("Identity Provider did not authenticate with the requested AuthnContext.");
//            }
//        }
//
        UaaUser user = createIfMissing(initialPrincipal, addNew, samlAuthorities, userAttributes);
        UaaPrincipal newPrincipal = new UaaPrincipal(user);
        UaaAuthentication newAuthentication = new UaaAuthentication(initialUaaAuthentication, newPrincipal);

        publish(new IdentityProviderAuthenticationSuccessEvent(user, newAuthentication, OriginKeys.SAML, identityZoneManager.getCurrentIdentityZoneId()));
//        if (samlConfig.isStoreCustomAttributes()) {
//            userDatabase.storeUserInfo(user.getId(),
//                    new UserInfo()
//                            .setUserAttributes(resultUaaAuthentication.getUserAttributes())
//                            .setRoles(new LinkedList(resultUaaAuthentication.getExternalGroups()))
//            );
//        }
//        configureRelayRedirect(relayState);
//
        return newAuthentication;
    }
//
//    private void process(Saml2AuthenticationToken token, Response response) {
//        String issuer = response.getIssuer().getValue();
//        log.debug(LogMessage.format("Processing SAML response from %s", issuer));
//        boolean responseSigned = response.isSigned();
//
//        OpenSaml4AuthenticationProvider.ResponseToken responseToken = new OpenSaml4AuthenticationProvider.ResponseToken(response, token);
//        Saml2ResponseValidatorResult result = this.responseSignatureValidator.convert(responseToken);
//        if (responseSigned) {
//            this.responseElementsDecrypter.accept(responseToken);
//        }
//        else if (!response.getEncryptedAssertions().isEmpty()) {
//            result = result.concat(new Saml2Error(Saml2ErrorCodes.INVALID_SIGNATURE,
//                    "Did not decrypt response [" + response.getID() + "] since it is not signed"));
//        }
//        result = result.concat(this.responseValidator.convert(responseToken));
//        boolean allAssertionsSigned = true;
//        for (Assertion assertion : response.getAssertions()) {
//            OpenSaml4AuthenticationProvider.AssertionToken assertionToken = new OpenSaml4AuthenticationProvider.AssertionToken(assertion, token);
//            result = result.concat(this.assertionSignatureValidator.convert(assertionToken));
//            allAssertionsSigned = allAssertionsSigned && assertion.isSigned();
//            if (responseSigned || assertion.isSigned()) {
//                this.assertionElementsDecrypter.accept(new OpenSaml4AuthenticationProvider.AssertionToken(assertion, token));
//            }
//            result = result.concat(this.assertionValidator.convert(assertionToken));
//        }
//        if (!responseSigned && !allAssertionsSigned) {
//            String description = "Either the response or one of the assertions is unsigned. "
//                    + "Please either sign the response or all of the assertions.";
//            result = result.concat(new Saml2Error(Saml2ErrorCodes.INVALID_SIGNATURE, description));
//        }
//        Assertion firstAssertion = CollectionUtils.firstElement(response.getAssertions());
//        if (firstAssertion != null && !hasName(firstAssertion)) {
//            Saml2Error error = new Saml2Error(Saml2ErrorCodes.SUBJECT_NOT_FOUND,
//                    "Assertion [" + firstAssertion.getID() + "] is missing a subject");
//            result = result.concat(error);
//        }
//
//        if (result.hasErrors()) {
//            Collection<Saml2Error> errors = result.getErrors();
//            if (this.logger.isTraceEnabled()) {
//                this.logger.debug("Found " + errors.size() + " validation errors in SAML response [" + response.getID()
//                        + "]: " + errors);
//            }
//            else if (this.logger.isDebugEnabled()) {
//                this.logger
//                        .debug("Found " + errors.size() + " validation errors in SAML response [" + response.getID() + "]");
//            }
//            Saml2Error first = errors.iterator().next();
//            throw createAuthenticationException(first.getErrorCode(), first.getDescription(), null);
//        }
//        else {
//            if (this.logger.isDebugEnabled()) {
//                this.logger.debug("Successfully processed SAML Response [" + response.getID() + "]");
//            }
//        }
//    }


    private Response parseResponse(String response) throws Saml2Exception, Saml2AuthenticationException {
        try {
            Document document = parserPool
                    .parse(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
            Element element = document.getDocumentElement();
            return (Response) responseUnmarshaller.unmarshall(element);
        } catch (Exception ex) {
            // TODO: Add error code
            throw new Saml2AuthenticationException(new Saml2Error("TODO", "TODO"), ex);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return authentication.equals(Saml2AuthenticationToken.class);
    }

//    @Override
//    public void setUserDetails(SAMLUserDetailsService userDetails) {
//        super.setUserDetails(userDetails);
//    }


    public void configureRelayRedirect(String relayState) {
        //configure relay state
        if (UaaUrlUtils.isUrl(relayState)) {
            RequestContextHolder.currentRequestAttributes()
                    .setAttribute(
                            UaaSavedRequestAwareAuthenticationSuccessHandler.URI_OVERRIDE_ATTRIBUTE,
                            relayState,
                            RequestAttributes.SCOPE_REQUEST
                    );
        }
    }

//    protected ExpiringUsernameAuthenticationToken getExpiringUsernameAuthenticationToken(Authentication authentication) {
//        return (ExpiringUsernameAuthenticationToken) super.authenticate(authentication);
//    }

    protected void publish(ApplicationEvent event) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(event);
        }
    }

    protected Set<String> filterSamlAuthorities(SamlIdentityProviderDefinition definition, Collection<? extends GrantedAuthority> samlAuthorities) {
        List<String> whiteList = of(definition.getExternalGroupsWhitelist()).orElse(Collections.EMPTY_LIST);
        Set<String> authorities = samlAuthorities.stream().map(s -> s.getAuthority()).collect(Collectors.toSet());
        Set<String> result = retainAllMatches(authorities, whiteList);
        log.debug(String.format("White listed external SAML groups:'%s'", result));
        return result;
    }

//    protected Collection<? extends GrantedAuthority> mapAuthorities(String origin, Collection<? extends GrantedAuthority> authorities) {
//        Collection<GrantedAuthority> result = new LinkedList<>();
//        log.debug("Mapping SAML authorities:" + authorities);
//        for (GrantedAuthority authority : authorities) {
//            String externalGroup = authority.getAuthority();
//            log.debug("Attempting to map external group: " + externalGroup);
//            for (ScimGroupExternalMember internalGroup : externalMembershipManager.getExternalGroupMapsByExternalGroup(externalGroup, origin, identityZoneManager.getCurrentIdentityZoneId())) {
//                String internalName = internalGroup.getDisplayName();
//                log.debug(String.format("Mapped external: '%s' to internal: '%s'", externalGroup, internalName));
//                result.add(new SimpleGrantedAuthority(internalName));
//            }
//        }
//        return result;
//    }

    private Collection<? extends GrantedAuthority> retrieveSamlAuthorities(SamlIdentityProviderDefinition definition, Response response) {
        if (definition.getAttributeMappings().get(GROUP_ATTRIBUTE_NAME) != null) {
            List<String> groupAttributeNames = getGroupAttributeNames(definition);

            Collection<SamlUserAuthority> authorities = new ArrayList<>();
//            response.getAssertions().stream()
//                    .filter(attribute -> groupAttributeNames.contains(attribute.getName()) || groupAttributeNames.contains(attribute.getFriendlyName()))
//                    .filter(attribute -> attribute.getAttributeValues() != null)
//                    .filter(attribute -> attribute.getAttributeValues().size() > 0)
//                    .forEach(attribute -> {
//                        for (XMLObject group : attribute.getAttributeValues()) {
//                            authorities.add(new SamlUserAuthority(getStringValue(attribute.getName(),
//                                    definition,
//                                    group)));
//                        }
//                    });

            return authorities;
        }
        return new ArrayList<>();
    }

    private List<String> getGroupAttributeNames(SamlIdentityProviderDefinition definition) {
        List<String> attributeNames = new LinkedList<>();

        if (definition.getAttributeMappings().get(GROUP_ATTRIBUTE_NAME) instanceof String value) {
            attributeNames.add(value);
        } else if (definition.getAttributeMappings().get(GROUP_ATTRIBUTE_NAME) instanceof Collection value) {
            attributeNames.addAll(value);
        }
        return attributeNames;
    }

    public MultiValueMap<String, String> retrieveUserAttributes(SamlIdentityProviderDefinition definition, Response response) {
        log.debug(String.format("Retrieving SAML user attributes [zone:%s, origin:%s]", definition.getZoneId(), definition.getIdpEntityAlias()));
        MultiValueMap<String, String> userAttributes = new LinkedMultiValueMap<>();
        List<Assertion> assertions = response.getAssertions();
        if (assertions.isEmpty()) {
            return userAttributes;
        }
        for (Assertion assertion : assertions) {
            if (assertion.getAttributeStatements() != null) {
                for (AttributeStatement statement : assertion.getAttributeStatements()) {
                    for (Attribute attribute : statement.getAttributes()) {
                        if (attribute.getAttributeValues() != null) {
                            for (XMLObject xmlObject : attribute.getAttributeValues()) {
                                String key = attribute.getName();
                                String value = getStringValue(key, definition, xmlObject);
                                if (value != null) {
                                    userAttributes.add(key, value);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (definition != null && definition.getAttributeMappings() != null) {
            for (Map.Entry<String, Object> attributeMapping : definition.getAttributeMappings().entrySet()) {
                Object attributeKey = attributeMapping.getValue();
                if (attributeKey instanceof String) {
                    if (userAttributes.get(attributeKey) != null) {
                        String key = attributeMapping.getKey();
                        userAttributes.addAll(key, userAttributes.get(attributeKey));
                    }
                }
            }
        }
//        if (credential.getAuthenticationAssertion() != null && credential.getAuthenticationAssertion().getAuthnStatements() != null) {
//            for (AuthnStatement statement : credential.getAuthenticationAssertion().getAuthnStatements()) {
//                if (statement.getAuthnContext() != null && statement.getAuthnContext().getAuthnContextClassRef() != null) {
//                    userAttributes.add(AUTHENTICATION_CONTEXT_CLASS_REFERENCE, statement.getAuthnContext().getAuthnContextClassRef().getAuthnContextClassRef());
//                }
//            }
//        }
        return userAttributes;
    }

    protected String getStringValue(String key, SamlIdentityProviderDefinition definition, XMLObject xmlObject) {
        String value = null;
        if (xmlObject instanceof XSString) {
            value = ((XSString) xmlObject).getValue();
        } else if (xmlObject instanceof XSAny) {
            value = ((XSAny) xmlObject).getTextContent();
        } else if (xmlObject instanceof XSInteger) {
            Integer i = ((XSInteger) xmlObject).getValue();
            value = i != null ? i.toString() : null;
        } else if (xmlObject instanceof XSBoolean) {
            XSBooleanValue b = ((XSBoolean) xmlObject).getValue();
            value = b != null && b.getValue() != null ? b.getValue().toString() : null;
        } else if (xmlObject instanceof XSDateTime) {
            Instant d = ((XSDateTime) xmlObject).getValue();
            value = d != null ? d.toString() : null;
        } else if (xmlObject instanceof XSQName) {
            QName name = ((XSQName) xmlObject).getValue();
            value = name != null ? name.toString() : null;
        } else if (xmlObject instanceof XSURI) {
            value = ((XSURI) xmlObject).getURI();
        } else if (xmlObject instanceof XSBase64Binary) {
            value = ((XSBase64Binary) xmlObject).getValue();
        }

        if (value != null) {
            log.debug(String.format("Found SAML user attribute %s of value %s [zone:%s, origin:%s]", key, value, definition.getZoneId(), definition.getIdpEntityAlias()));
            return value;
        } else if (xmlObject != null) {
            log.debug(String.format("SAML user attribute %s at is not of type XSString or other recognizable type, %s [zone:%s, origin:%s]", key, xmlObject.getClass().getName(), definition.getZoneId(), definition.getIdpEntityAlias()));
        }
        return null;
    }

    protected UaaUser createIfMissing(UaaPrincipal samlPrincipal, boolean addNew, Collection<? extends GrantedAuthority> authorities, MultiValueMap<String, String> userAttributes) {
        UaaUser user = null;
        String invitedUserId = null;
        boolean is_invitation_acceptance = isAcceptedInvitationAuthentication();
        if (is_invitation_acceptance) {
            invitedUserId = (String) RequestContextHolder.currentRequestAttributes().getAttribute("user_id", RequestAttributes.SCOPE_SESSION);
            user = userDatabase.retrieveUserById(invitedUserId);
            if (userAttributes.getFirst(EMAIL_ATTRIBUTE_NAME) != null) {
                if (!userAttributes.getFirst(EMAIL_ATTRIBUTE_NAME).equalsIgnoreCase(user.getEmail())) {
                    throw new BadCredentialsException("SAML User email mismatch. Authenticated email doesn't match invited email.");
                }
            } else {
                userAttributes = new LinkedMultiValueMap<>(userAttributes);
                userAttributes.add(EMAIL_ATTRIBUTE_NAME, user.getEmail());
            }
            addNew = false;
            if (user.getUsername().equals(user.getEmail()) && !user.getUsername().equals(samlPrincipal.getName())) {
                user = user.modifyUsername(samlPrincipal.getName());
            }
            publish(new InvitedUserAuthenticatedEvent(user));
            user = userDatabase.retrieveUserById(invitedUserId);
        }

        boolean userModified = false;
        UaaUser userWithSamlAttributes = getUser(samlPrincipal, userAttributes);
        try {
            if (user == null) {
                user = userDatabase.retrieveUserByName(samlPrincipal.getName(), samlPrincipal.getOrigin());
            }
        } catch (UsernameNotFoundException e) {
            UaaUserPrototype uaaUser = userDatabase.retrieveUserPrototypeByEmail(userWithSamlAttributes.getEmail(), samlPrincipal.getOrigin());
            if (uaaUser != null) {
                userModified = true;
                user = new UaaUser(uaaUser.withUsername(samlPrincipal.getName()));
            } else {
                if (!addNew) {
                    throw new SamlLoginException("SAML user does not exist. "
                            + "You can correct this by creating a shadow user for the SAML user.", e);
                }
                publish(new NewUserAuthenticatedEvent(userWithSamlAttributes));
                try {
                    user = new UaaUser(userDatabase.retrieveUserPrototypeByName(samlPrincipal.getName(), samlPrincipal.getOrigin()));
                } catch (UsernameNotFoundException ex) {
                    throw new BadCredentialsException("Unable to establish shadow user for SAML user:" + samlPrincipal.getName());
                }
            }
        }
        if (haveUserAttributesChanged(user, userWithSamlAttributes)) {
            userModified = true;
            user = user.modifyAttributes(userWithSamlAttributes.getEmail(),
                    userWithSamlAttributes.getGivenName(),
                    userWithSamlAttributes.getFamilyName(),
                    userWithSamlAttributes.getPhoneNumber(),
                    userWithSamlAttributes.getExternalId(),
                    user.isVerified() || userWithSamlAttributes.isVerified());
        }
        publish(
                new ExternalGroupAuthorizationEvent(
                        user,
                        userModified,
                        authorities,
                        true
                )
        );
        user = userDatabase.retrieveUserById(user.getId());
        return user;
    }

    protected UaaUser getUser(UaaPrincipal principal, MultiValueMap<String, String> userAttributes) {
        if (principal.getName() == null && userAttributes.getFirst(EMAIL_ATTRIBUTE_NAME) == null) {
            throw new BadCredentialsException("Cannot determine username from credentials supplied");
        }

        String name = principal.getName();
        return UaaUser.createWithDefaults(u ->
                u.withId(OriginKeys.NotANumber)
                        .withUsername(name)
                        .withEmail(userAttributes.getFirst(EMAIL_ATTRIBUTE_NAME))
                        .withPhoneNumber(userAttributes.getFirst(PHONE_NUMBER_ATTRIBUTE_NAME))
                        .withPassword("")
                        .withGivenName(userAttributes.getFirst(GIVEN_NAME_ATTRIBUTE_NAME))
                        .withFamilyName(userAttributes.getFirst(FAMILY_NAME_ATTRIBUTE_NAME))
                        .withAuthorities(Collections.emptyList())
                        .withVerified(Boolean.valueOf(userAttributes.getFirst(EMAIL_VERIFIED_ATTRIBUTE_NAME)))
                        .withOrigin(principal.getOrigin() != null ? principal.getOrigin() : OriginKeys.LOGIN_SERVER)
                        .withExternalId(name)
                        .withZoneId(principal.getZoneId())
        );
    }

    protected boolean haveUserAttributesChanged(UaaUser existingUser, UaaUser user) {
        return existingUser.isVerified() != user.isVerified() ||
                !StringUtils.equals(existingUser.getGivenName(), user.getGivenName()) ||
                !StringUtils.equals(existingUser.getFamilyName(), user.getFamilyName()) ||
                !StringUtils.equals(existingUser.getPhoneNumber(), user.getPhoneNumber()) ||
                !StringUtils.equals(existingUser.getEmail(), user.getEmail()) ||
                !StringUtils.equals(existingUser.getExternalId(), user.getExternalId());
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.eventPublisher = applicationEventPublisher;
    }
}