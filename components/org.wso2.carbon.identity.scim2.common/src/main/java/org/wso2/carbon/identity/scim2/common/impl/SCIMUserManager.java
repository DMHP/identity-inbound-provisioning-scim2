/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.scim2.common.impl;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.mgt.policy.PolicyViolationException;
import org.wso2.carbon.identity.scim2.common.exceptions.IdentitySCIMException;
import org.wso2.carbon.identity.scim2.common.group.SCIMGroupHandler;
import org.wso2.carbon.identity.scim2.common.utils.AttributeMapper;
import org.wso2.carbon.identity.scim2.common.utils.SCIMCommonConstants;
import org.wso2.carbon.identity.scim2.common.utils.SCIMCommonUtils;
import org.wso2.carbon.user.api.ClaimMapping;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.PaginatedUserStoreManager;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.claim.ClaimManager;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.model.Condition;
import org.wso2.carbon.user.core.model.ExpressionAttribute;
import org.wso2.carbon.user.core.model.ExpressionCondition;
import org.wso2.carbon.user.core.model.ExpressionOperation;
import org.wso2.carbon.user.core.model.OperationalCondition;
import org.wso2.carbon.user.core.model.OperationalOperation;
import org.wso2.carbon.user.core.model.UserClaimSearchEntry;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.charon3.core.attributes.Attribute;
import org.wso2.charon3.core.attributes.MultiValuedAttribute;
import org.wso2.charon3.core.attributes.SimpleAttribute;
import org.wso2.charon3.core.config.SCIMUserSchemaExtensionBuilder;
import org.wso2.charon3.core.exceptions.BadRequestException;
import org.wso2.charon3.core.exceptions.CharonException;
import org.wso2.charon3.core.exceptions.ConflictException;
import org.wso2.charon3.core.exceptions.NotFoundException;
import org.wso2.charon3.core.exceptions.NotImplementedException;
import org.wso2.charon3.core.extensions.UserManager;
import org.wso2.charon3.core.objects.Group;
import org.wso2.charon3.core.objects.User;
import org.wso2.charon3.core.protocol.ResponseCodeConstants;
import org.wso2.charon3.core.schema.SCIMConstants;
import org.wso2.charon3.core.schema.SCIMResourceSchemaManager;
import org.wso2.charon3.core.schema.SCIMResourceTypeSchema;
import org.wso2.charon3.core.utils.AttributeUtil;
import org.wso2.charon3.core.utils.ResourceManagerUtil;
import org.wso2.charon3.core.utils.codeutils.ExpressionNode;
import org.wso2.charon3.core.utils.codeutils.Node;
import org.wso2.charon3.core.utils.codeutils.OperationNode;
import org.wso2.charon3.core.utils.codeutils.SearchRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.wso2.carbon.identity.scim2.common.utils.SCIMCommonUtils.isFilteringEnhancementsEnabled;

public class SCIMUserManager implements UserManager {

    public static final String FILTERING_DELIMITER = "*";
    public static final String SQL_FILTERING_DELIMITER = "%";
    private static final String SCIM2_COMPLIANCE = "scim2.compliance";
    private static final String ERROR_CODE_INVALID_USERNAME = "31301";
    private static Log log = LogFactory.getLog(SCIMUserManager.class);
    private UserStoreManager carbonUM = null;
    private ClaimManager carbonClaimManager = null;
    private static final int MAX_ITEM_LIMIT_UNLIMITED = -1;
    private static final String ENABLE_PAGINATED_USER_STORE = "SCIM.EnablePaginatedUserStore";

    public SCIMUserManager(UserStoreManager carbonUserStoreManager, ClaimManager claimManager) {
        carbonUM = carbonUserStoreManager;
        carbonClaimManager = claimManager;
    }

    @Override
    public User createUser(User user, Map<String, Boolean> requiredAttributes)
            throws CharonException, ConflictException, BadRequestException {
        String userStoreName = null;

        try {
            String userStoreDomainFromSP = getUserStoreDomainFromSP();
            if (userStoreDomainFromSP != null) {
                userStoreName = userStoreDomainFromSP;
            }
        } catch (IdentityApplicationManagementException e) {
            throw new CharonException("Error retrieving User Store name. ", e);
        }

        StringBuilder userName = new StringBuilder();

        if (StringUtils.isNotBlank(userStoreName)) {
            // if we have set a user store under provisioning configuration - we should only use that.
            String currentUserName = user.getUserName();
            currentUserName = UserCoreUtil.removeDomainFromName(currentUserName);
            user.setUserName(userName.append(userStoreName)
                    .append(CarbonConstants.DOMAIN_SEPARATOR).append(currentUserName)
                    .toString());
        }

        String userStoreDomainName = IdentityUtil.extractDomainFromName(user.getUserName());
        if(StringUtils.isNotBlank(userStoreDomainName) && !isSCIMEnabled(userStoreDomainName)){
            throw new CharonException("Cannot add user through scim to user store " + ". SCIM is not " +
                    "enabled for user store " + userStoreDomainName);
        }

        try {

            //Persist in carbon user store
            if (log.isDebugEnabled()) {
                log.debug("Creating user: " + user.getUserName());
            }
                /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
            SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);
            Map<String, String> claimsMap = AttributeMapper.getClaimsMap(user);

                /*skip groups attribute since we map groups attribute to actual groups in ldap.
                and do not update it as an attribute in user schema*/
            if (claimsMap.containsKey(SCIMConstants.UserSchemaConstants.GROUP_URI)) {
                claimsMap.remove(SCIMConstants.UserSchemaConstants.GROUP_URI);
            }

            /* Skip roles list since we map SCIM groups to local roles internally. It shouldn't be allowed to
                manipulate SCIM groups from user endpoint as this attribute has a mutability of "readOnly". Group
                changes must be applied via Group Resource */
            if (claimsMap.containsKey(SCIMConstants.UserSchemaConstants.ROLES_URI + "." + SCIMConstants.DEFAULT)) {
                claimsMap.remove(SCIMConstants.UserSchemaConstants.ROLES_URI);
            }

            if (carbonUM.isExistingUser(user.getUserName())) {
                String error = "User with the name: " + user.getUserName() + " already exists in the system.";
                throw new ConflictException(error);
            }
            if (claimsMap.containsKey(SCIMConstants.UserSchemaConstants.USER_NAME_URI)) {
                claimsMap.remove(SCIMConstants.UserSchemaConstants.USER_NAME_URI);
            }
            Map<String, String> scimToLocalClaimsMap = SCIMCommonUtils.getSCIMtoLocalMappings();
            Map<String, String> claimsInLocalDialect = SCIMCommonUtils.convertSCIMtoLocalDialect(claimsMap);
            carbonUM.addUser(user.getUserName(), user.getPassword(), null, claimsInLocalDialect, null);
            log.info("User: " + user.getUserName() + " is created through SCIM.");

            // Get required SCIM Claims in local claim dialect.
            List<String> requiredClaimsInLocalDialect = getRequiredClaimsInLocalDialect(scimToLocalClaimsMap,
                    requiredAttributes);
            // Get the user from the user store in order to get the default attributes during the user creation
            // response.
            user = this.getSCIMUser(user.getUserName(), requiredClaimsInLocalDialect, scimToLocalClaimsMap);
            // Set the schemas of the scim user.
            user.setSchemas();
        } catch (UserStoreException e) {
            handleErrorsOnUserNameAndPasswordPolicy(e);
            String errMsg = "Error in adding the user: " + user.getUserName() + " to the user store. ";
            errMsg += e.getMessage();
            throw new CharonException(errMsg, e);
        }
        return user;
    }

    private void handleErrorsOnUserNameAndPasswordPolicy(Throwable e) throws BadRequestException {

        String specCompliance = System.getProperty(SCIM2_COMPLIANCE);
        if (Boolean.parseBoolean(specCompliance)) {
            int i = 0; // this variable is used to avoid endless loop if the e.getCause never becomes null.
            while (e != null && i < 10) {

                if (e instanceof UserStoreException && e.getMessage().contains(ERROR_CODE_INVALID_USERNAME)) {
                    throw new BadRequestException(e.getMessage(), ResponseCodeConstants.INVALID_VALUE);
                }
                if (e instanceof PolicyViolationException) {
                    throw new BadRequestException(e.getMessage(), ResponseCodeConstants.INVALID_VALUE);
                }
                e = e.getCause();
                i++;
            }
        }
    }

    @Override
    public User getUser(String userId, Map<String, Boolean> requiredAttributes) throws CharonException {
        if (log.isDebugEnabled()) {
            log.debug("Retrieving user: " + userId);
        }
        User scimUser;
        try {
            //get the user name of the user with this id
            String userIdLocalClaim = SCIMCommonUtils.getSCIMtoLocalMappings().get(SCIMConstants
                    .CommonSchemaConstants.ID_URI);
            String[] userNames = null;
            if (StringUtils.isNotBlank(userIdLocalClaim)) {
                userNames = carbonUM.getUserList(userIdLocalClaim, userId, UserCoreConstants.DEFAULT_PROFILE);
            }

            if (userNames == null || userNames.length == 0) {
                if (log.isDebugEnabled()) {
                    log.debug("User with SCIM id: " + userId + " does not exist in the system.");
                }
                return null;
            } else {
                //get Claims related to SCIM claim dialect
                Map<String, String> scimToLocalClaimsMap = SCIMCommonUtils.getSCIMtoLocalMappings();
                List<String> requiredClaims = getOnlyRequiredClaims(scimToLocalClaimsMap.keySet(), requiredAttributes);
                List<String> requiredClaimsInLocalDialect;
                if (MapUtils.isNotEmpty(scimToLocalClaimsMap)) {
                    scimToLocalClaimsMap.keySet().retainAll(requiredClaims);
                    requiredClaimsInLocalDialect = new ArrayList<>(scimToLocalClaimsMap.values());
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("SCIM to Local Claim mappings list is empty.");
                    }
                    requiredClaimsInLocalDialect = new ArrayList<>();
                }
                //we assume (since id is unique per user) only one user exists for a given id
                scimUser = this.getSCIMUser(userNames[0], requiredClaimsInLocalDialect, scimToLocalClaimsMap);
                //set the schemas of the scim user
                scimUser.setSchemas();
                log.info("User: " + scimUser.getUserName() + " is retrieved through SCIM.");
            }

        } catch (UserStoreException e) {
            throw new CharonException("Error in getting user information from Carbon User Store for" +
                    "user: " + userId, e);
        }
        return scimUser;
    }

    @Override
    public void deleteUser(String userId) throws NotFoundException, CharonException {
        if (log.isDebugEnabled()) {
            log.debug("Deleting user: " + userId);
        }
        //get the user name of the user with this id
        String[] userNames = null;
        String userName = null;
        try {
            /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
            SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);
            String userIdLocalClaim = SCIMCommonUtils.getSCIMtoLocalMappings().get(SCIMConstants
                    .CommonSchemaConstants.ID_URI);
            if (StringUtils.isNotBlank(userIdLocalClaim)) {
                userNames = carbonUM.getUserList(userIdLocalClaim, userId, UserCoreConstants.DEFAULT_PROFILE);
            }
            String userStoreDomainFromSP = null;
            try {
                userStoreDomainFromSP = getUserStoreDomainFromSP();
            } catch (IdentityApplicationManagementException e) {
                throw new CharonException("Error retrieving User Store name. ", e);
            }
            if (userNames == null || userNames.length == 0) {
                //resource with given id not found
                if (log.isDebugEnabled()) {
                    log.debug("User with id: " + userId + " not found.");
                }
                throw new NotFoundException();
            } else if (userStoreDomainFromSP != null &&
                    !(userStoreDomainFromSP
                            .equalsIgnoreCase(IdentityUtil.extractDomainFromName(userNames[0])))) {
                throw new CharonException("User :" + userNames[0] + "is not belong to user store " +
                        userStoreDomainFromSP + "Hence user updating fail");
            } else {
                //we assume (since id is unique per user) only one user exists for a given id
                userName = userNames[0];
                carbonUM.deleteUser(userName);
                log.info("User: " + userName + " is deleted through SCIM.");
            }

        } catch (org.wso2.carbon.user.core.UserStoreException e) {
            throw new CharonException("Error in deleting user: " + userName, e);
        }

    }

    @Override
    public List<Object> listUsersWithGET(Node rootNode, int startIndex, int count, String sortBy, String sortOrder,
                                         String domainName, Map<String, Boolean> requiredAttributes)
            throws CharonException, NotImplementedException {

        if (sortBy != null || sortOrder != null) {
            throw new NotImplementedException("Sorting is not supported");
        } else if (rootNode != null) {
            return filterUsers(rootNode, requiredAttributes, startIndex, count, sortBy, sortOrder, domainName);
        } else {
            return listUsers(requiredAttributes, startIndex, count, sortBy, sortOrder, domainName);
        }
    }

    @Override
    public List<Object> listUsersWithPost(SearchRequest searchRequest, Map<String, Boolean> requiredAttributes)
            throws CharonException, NotImplementedException, BadRequestException {
        return listUsersWithGET(searchRequest.getFilter(), searchRequest.getStartIndex(), searchRequest.getCount(),
                searchRequest.getSortBy(), searchRequest.getSortOder(), searchRequest.getDomainName(),
                requiredAttributes);
    }

    /**
     * Method to list users for given conditions.
     *
     * @param requiredAttributes Required attributes for the response
     * @param offset             Starting index of the count
     * @param limit              Counting value
     * @param sortBy             SortBy
     * @param sortOrder          Sorting order
     * @param domainName         Name of the user store
     * @return User list with detailed attributes
     * @throws CharonException Error while listing users
     */
    private List<Object> listUsers(Map<String, Boolean> requiredAttributes, int offset, int limit, String sortBy,
            String sortOrder, String domainName) throws CharonException {

        List<Object> users = new ArrayList<>();
        //0th index is to store total number of results.
        users.add(0);
        String[] userNames;
        if (StringUtils.isNotEmpty(domainName)) {
            userNames = listAllUsernamesByDomain(domainName);
        } else {
            userNames = listAllUsernamesAcrossAllDomains();
        }

        if (ArrayUtils.isEmpty(userNames)) {
            if (log.isDebugEnabled()) {
                String message = String.format("There are no users who comply with the requested conditions: "
                        + "startIndex = %d, count = %d", offset, limit);
                if (StringUtils.isNotEmpty(domainName)) {
                    message = String.format(message + ", domain = %s", domainName);
                }
                log.debug(message);
            }
        } else {
            users.set(0, userNames.length); // Set total number of results to 0th index.
            users.addAll(getUserDetails(userNames, requiredAttributes)); // Set user details from index 1.
        }
        return users;
    }

    /**
     * Method to list usernames of all users from a specific user store.
     *
     * @param domainName Name of the user store
     * @return Usernames list
     * @throws CharonException Error while listing usernames
     */
    private String[] listAllUsernamesByDomain(String domainName) throws CharonException {

        String[] userNames = null;
        try {
            Map<String, String> scimToLocalClaimsMap = SCIMCommonUtils.getSCIMtoLocalMappings();
            String userIdLocalClaim = scimToLocalClaimsMap.get(SCIMConstants.CommonSchemaConstants.ID_URI);
            String claimValue = domainName.toUpperCase() + CarbonConstants.DOMAIN_SEPARATOR + SCIMCommonConstants.ANY;
            if (StringUtils.isNotBlank(userIdLocalClaim)) {
                userNames = carbonUM.getUserList(userIdLocalClaim, claimValue, null);
            }
            return userNames;
        } catch (UserStoreException e) {
            throw new CharonException(String.format("Error while listing usernames from domain: %s.", domainName), e);
        }
    }

    /**
     * Method to list usernames of all users across all user stores.
     *
     * @return Usernames list
     * @throws CharonException Error while listing usernames
     */
    private String[] listAllUsernamesAcrossAllDomains() throws CharonException {

        String[] userNames = null;
        try {
            Map<String, String> scimToLocalClaimsMap = SCIMCommonUtils.getSCIMtoLocalMappings();
            String userIdLocalClaim = scimToLocalClaimsMap.get(SCIMConstants.CommonSchemaConstants.ID_URI);
            if (StringUtils.isNotBlank(userIdLocalClaim)) {
                userNames = carbonUM.getUserList(userIdLocalClaim, SCIMCommonConstants.ANY, null);
            }
            return userNames;
        } catch (UserStoreException e) {
            throw new CharonException("Error while listing usernames across all domains. ", e);
        }
    }

    /**
     * Method to get user details of usernames.
     *
     * @param userNames          Array of usernames
     * @param requiredAttributes Required attributes for the response
     * @return User list with detailed attributes
     * @throws CharonException Error while retrieving users
     */
    private List<Object> getUserDetails(String[] userNames, Map<String, Boolean> requiredAttributes)
            throws CharonException {

        List<Object> users = new ArrayList<>();
        try {
            Map<String, String> scimToLocalClaimsMap = SCIMCommonUtils.getSCIMtoLocalMappings();
            List<String> requiredClaims = getOnlyRequiredClaims(scimToLocalClaimsMap.keySet(), requiredAttributes);
            List<String> requiredClaimsInLocalDialect;
            if (MapUtils.isNotEmpty(scimToLocalClaimsMap)) {
                scimToLocalClaimsMap.keySet().retainAll(requiredClaims);
                requiredClaimsInLocalDialect = new ArrayList<>(scimToLocalClaimsMap.values());
            } else {
                requiredClaimsInLocalDialect = new ArrayList<>();
            }

            User[] scimUsers;
            if (isPaginatedUserStoreAvailable() && carbonUM instanceof PaginatedUserStoreManager) {
                // Retrieve all SCIM users at once.
                scimUsers = this.getSCIMUsers(userNames, requiredClaimsInLocalDialect, scimToLocalClaimsMap);
                users.addAll(Arrays.asList(scimUsers));
            } else {
                // Retrieve SCIM users one by one.
                retriveSCIMUsers(users, userNames, requiredClaimsInLocalDialect, scimToLocalClaimsMap);
            }
        } catch (UserStoreException e) {
            throw new CharonException("Error while retrieving users from user store.", e);
        }
        return users;
    }

    private void retriveSCIMUsers(List<Object> users, String[] userNames, List<String> requiredClaims,
            Map<String, String> scimToLocalClaimsMap) throws CharonException {
        for (String userName : userNames) {
            if (userName.contains(UserCoreConstants.NAME_COMBINER)) {
                userName = userName.split("\\" + UserCoreConstants.NAME_COMBINER)[0];
            }
            String userStoreDomainName = IdentityUtil.extractDomainFromName(userName);
            if (isSCIMEnabled(userStoreDomainName)) {
                if (log.isDebugEnabled()) {
                    log.debug("SCIM is enabled for the user-store domain : " + userStoreDomainName + ". "
                            + "Including user : " + userName + " in the response.");
                }
                User scimUser = this.getSCIMUser(userName, requiredClaims, scimToLocalClaimsMap);
                if (scimUser != null) {
                    Map<String, Attribute> attrMap = scimUser.getAttributeList();
                    if (attrMap != null && !attrMap.isEmpty()) {
                        users.add(scimUser);
                    }
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("SCIM is disabled for the user-store domain : " + userStoreDomainName + ". "
                            + "Hence user : " + userName + " in this domain is excluded in the response.");
                }
            }
        }
    }

    @Override
    public User updateUser(User user, Map<String, Boolean> requiredAttributes) throws CharonException,
            BadRequestException {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Updating user: " + user.getUserName());
            }

            /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
            SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);
            //get user claim values
            Map<String, String> claims = AttributeMapper.getClaimsMap(user);

            //check if username of the updating user existing in the userstore.
            try {
                String userStoreDomainFromSP = getUserStoreDomainFromSP();
                SCIMResourceTypeSchema schema = SCIMResourceSchemaManager.getInstance().getUserResourceSchema();
                User oldUser = this.getUser(user.getId(), ResourceManagerUtil.getAllAttributeURIs(schema));
                if (userStoreDomainFromSP != null && !userStoreDomainFromSP
                        .equalsIgnoreCase(IdentityUtil.extractDomainFromName(oldUser.getUserName()))) {
                    throw new CharonException("User :" + oldUser.getUserName() + "is not belong to user store " +
                            userStoreDomainFromSP + "Hence user updating fail");
                }
                if (getUserStoreDomainFromSP() != null &&
                        !UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME.equalsIgnoreCase(getUserStoreDomainFromSP())) {
                    user.setUserName(UserCoreUtil
                            .addDomainToName(UserCoreUtil.removeDomainFromName(user.getUserName()),
                                    getUserStoreDomainFromSP()));
                }
            } catch (IdentityApplicationManagementException e) {
                throw new CharonException("Error retrieving User Store name. ", e);
            }
            if (!carbonUM.isExistingUser(user.getUserName())) {
                throw new CharonException("User name is immutable in carbon user store.");
            }

                /*skip groups attribute since we map groups attribute to actual groups in ldap.
                and do not update it as an attribute in user schema*/
            if (claims.containsKey(SCIMConstants.UserSchemaConstants.GROUP_URI)) {
                claims.remove(SCIMConstants.UserSchemaConstants.GROUP_URI);
            }
            
                /* Skip roles list since we map SCIM groups to local roles internally. It shouldn't be allowed to
                manipulate SCIM groups from user endpoint as this attribute has a mutability of "readOnly". Group
                changes must be applied via Group Resource */
            if (claims.containsKey(SCIMConstants.UserSchemaConstants.ROLES_URI + "." + SCIMConstants.DEFAULT)) {
                claims.remove(SCIMConstants.UserSchemaConstants.ROLES_URI);
            }

            if (claims.containsKey(SCIMConstants.UserSchemaConstants.USER_NAME_URI)) {
                claims.remove(SCIMConstants.UserSchemaConstants.USER_NAME_URI);
            }

            Map<String, String> scimToLocalClaimsMap = SCIMCommonUtils.getSCIMtoLocalMappings();
            List<String> requiredClaims = getOnlyRequiredClaims(scimToLocalClaimsMap.keySet(), requiredAttributes);
            List<String> requiredClaimsInLocalDialect;
            if (MapUtils.isNotEmpty(scimToLocalClaimsMap)) {
                scimToLocalClaimsMap.keySet().retainAll(requiredClaims);
                requiredClaimsInLocalDialect = new ArrayList<>(scimToLocalClaimsMap.values());
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("SCIM to Local Claim mappings list is empty.");
                }
                requiredClaimsInLocalDialect = new ArrayList<>();
            }

            // Get existing user claims.
            Map<String, String> oldClaimList = carbonUM.getUserClaimValues(user.getUserName(), requiredClaimsInLocalDialect
                    .toArray(new String[requiredClaimsInLocalDialect.size()]), null);

            // Get user claims mapped from SCIM dialect to WSO2 dialect.
            Map<String, String> claimValuesInLocalDialect = SCIMCommonUtils.convertSCIMtoLocalDialect(claims);

            updateUserClaims(user, oldClaimList, claimValuesInLocalDialect);

            //if password is updated, set it separately
            if (user.getPassword() != null) {
                carbonUM.updateCredentialByAdmin(user.getUserName(), user.getPassword());
            }
            log.info("User: " + user.getUserName() + " updated through SCIM.");
            return getUser(user.getId(),requiredAttributes);
        } catch (UserStoreException e) {
            handleErrorsOnUserNameAndPasswordPolicy(e);
            throw new CharonException("Error while updating attributes of user: " + user.getUserName(), e);
        } catch (BadRequestException | CharonException e) {
            throw new CharonException("Error occured while trying to update the user");
        }
    }

    /**
     * Filter users using multi-attribute filters or single attribute filters with pagination.
     *
     * @param node
     * @param requiredAttributes
     * @param offset
     * @param limit
     * @param sortBy
     * @param sortOrder
     * @param domainName
     * @return
     * @throws NotImplementedException
     * @throws CharonException
     */
    private List<Object> filterUsers(Node node, Map<String, Boolean> requiredAttributes, int offset, int limit,
                                     String sortBy, String sortOrder, String domainName)
            throws NotImplementedException, CharonException {

        // Handle single attribute search.

        if (node instanceof ExpressionNode) {
            return filterUsersBySingleAttribute((ExpressionNode) node, requiredAttributes, offset, limit, sortBy,
                    sortOrder, domainName);
        } else if (node instanceof OperationNode) {
            if (log.isDebugEnabled())
                log.debug("Listing users by multi attribute filter");
            List<Object> filteredUsers = new ArrayList<>();

            // 0th index is to store total number of results.
            filteredUsers.add(0);

            // Support multi attribute filtering.
            return getMultiAttributeFilteredUsers(node, requiredAttributes, offset, limit, sortBy, sortOrder,
                    domainName, filteredUsers);
        } else {
            throw new CharonException("Unknown operation. Not either an expression node or an operation node.");
        }
    }

    /**
     * Method to filter users for a filter with a single attribute.
     *
     * @param node               Expression node for single attribute filtering
     * @param requiredAttributes Required attributes for the response
     * @param offset             Starting index of the count
     * @param limit              Counting value
     * @param sortBy             SortBy
     * @param sortOrder          Sorting order
     * @param domainName         Domain to run the filter
     * @return User list with detailed attributes
     * @throws CharonException Error while filtering
     */
    private List<Object> filterUsersBySingleAttribute(ExpressionNode node, Map<String, Boolean> requiredAttributes,
            int offset, int limit, String sortBy, String sortOrder, String domainName) throws CharonException {

        String[] userNames;

        if (log.isDebugEnabled()) {
            log.debug(String.format("Listing users by filter: %s %s %s", node.getAttributeValue(), node.getOperation(),
                    node.getValue()));
        }

        // Check whether the filter operation is supported by the users endpoint.
        if (isFilteringNotSupported(node.getOperation())) {
            String errorMessage =
                    "Filter operation: " + node.getOperation() + " is not supported for filtering in users endpoint.";
            throw new CharonException(errorMessage);
        }
        domainName = resolveDomainName(domainName, node);
        try {
            userNames = filterUsersUsingLegacyAPIs(node, limit, offset, domainName);
        } catch (NotImplementedException e) {
            String errorMessage = String.format("System does not support filter operator: %s", node.getOperation());
            throw new CharonException(errorMessage, e);
        }
        return getDetailedUsers(userNames, requiredAttributes);
    }

    /**
     * Method to resolve the domain name.
     *
     * @param domainName Domain to run the filter
     * @param node       Expression node for single attribute filtering
     * @return Resolved domainName
     * @throws CharonException
     */
    private String resolveDomainName(String domainName, ExpressionNode node) throws CharonException {

        try {
            // Extract the domain name if the domain name is embedded in the filter attribute value.
            domainName = resolveDomainNameInAttributeValue(domainName, node);
        } catch (BadRequestException e) {
            String errorMessage = String
                    .format("Domain parameter: %s in request does not match with the domain name in the attribute "
                                    + "value: %s ", domainName, node.getValue());
            throw new CharonException(errorMessage, e);
        }
        // Get domain name according to Filter Enhancements properties as in identity.xml
        if (StringUtils.isEmpty(domainName)) {
            domainName = getFilteredDomainName(node);
        }
        return domainName;
    }

    /**
     * Update the domain parameter from the domain in attribute value and update the value in the expression node to the
     * newly extracted value.
     *
     * @param domainName Domain name in the filter request
     * @param node       Expression node
     * @return Domain name extracted from the attribute value
     * @throws BadRequestException Domain miss match in domain parameter and attribute value
     */
    private String resolveDomainNameInAttributeValue(String domainName, ExpressionNode node)
            throws BadRequestException {

        String extractedDomain;
        String attributeName = node.getAttributeValue();
        String filterOperation = node.getOperation();
        String attributeValue = node.getValue();

        if (isDomainNameEmbeddedInAttributeValue(filterOperation, attributeName, attributeValue)) {
            int indexOfDomainSeparator = attributeValue.indexOf(CarbonConstants.DOMAIN_SEPARATOR);
            extractedDomain = attributeValue.substring(0, indexOfDomainSeparator).toUpperCase();

            // Update then newly extracted attribute value in the expression node.
            int startingIndexOfAttributeValue = indexOfDomainSeparator + 1;
            node.setValue(attributeValue.substring(startingIndexOfAttributeValue));

            // Check whether the domain name is equal to the extracted domain name from attribute value.
            if (StringUtils.isNotEmpty(domainName) && StringUtils.isNotEmpty(extractedDomain) && !extractedDomain
                    .equalsIgnoreCase(domainName))
                throw new BadRequestException(String.format(
                        " Domain name %s in the domain parameter does not match with the domain name %s in search "
                                + "attribute value of %s claim.", domainName, extractedDomain, attributeName));

            if (StringUtils.isEmpty(domainName) && StringUtils.isNotEmpty(extractedDomain)) {
                if (log.isDebugEnabled())
                    log.debug(String.format("Domain name %s set from the domain name in the attribute value %s ",
                            extractedDomain, attributeValue));
                return extractedDomain;
            }
        }
        return domainName;
    }

    /**
     * Method to verify whether there is a domain in the attribute value.
     *
     * @param filterOperation Operation of the expression node
     * @param attributeName   Attribute name of the expression node
     * @param attributeValue  Value of the expression node
     * @return Whether there is a domain embedded to the attribute value
     */
    private boolean isDomainNameEmbeddedInAttributeValue(String filterOperation, String attributeName,
            String attributeValue) {

        // Checks whether the domain separator is in the attribute value.
        if (StringUtils.contains(attributeValue, CarbonConstants.DOMAIN_SEPARATOR)) {

            // Checks whether the attribute name is username or group uri.
            if (StringUtils.equals(attributeName, SCIMConstants.UserSchemaConstants.USER_NAME_URI) || StringUtils
                    .equals(attributeName, SCIMConstants.UserSchemaConstants.GROUP_URI)) {

                // Checks whether the operator is equal to EQ, SW, EW, CO.
                if (SCIMCommonConstants.EQ.equalsIgnoreCase(filterOperation) || SCIMCommonConstants.SW
                        .equalsIgnoreCase(filterOperation) || SCIMCommonConstants.CO.equalsIgnoreCase(filterOperation)
                        || SCIMCommonConstants.EW.equalsIgnoreCase(filterOperation)) {
                    if (log.isDebugEnabled())
                        log.debug(String.format("Attribute value %s is embedded with a domain in %s claim, ",
                                attributeValue, attributeName));
                    // If all the above conditions are true, then a domain is embedded to the attribute value.
                    return true;
                }
            }
        }
        // If no domain name in the attribute value, return false.
        return false;
    }

    /**
     * Validate whether filter enhancements are enabled and then return primary default domain name as the domain to
     * be filtered.
     *
     * @param node Expression node
     * @return PRIMARY domainName if property enabled, Null otherwise.
     */
    private String getFilteredDomainName(ExpressionNode node) {

        // Set filter values.
        String attributeName = node.getAttributeValue();
        String filterOperation = node.getOperation();
        String attributeValue = node.getValue();

        if (isFilteringEnhancementsEnabled()) {
            if (SCIMCommonConstants.EQ.equalsIgnoreCase(filterOperation)) {
                if (StringUtils.equals(attributeName, SCIMConstants.UserSchemaConstants.USER_NAME_URI) && !StringUtils
                        .contains(attributeValue, CarbonConstants.DOMAIN_SEPARATOR)) {
                    return UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME;
                }
            }
        }
        return null;
    }

    /**
     * Method to filter users if the user store is not an instance of PaginatedUserStoreManager and
     * ENABLE_PAGINATED_USER_STORE is not enabled.
     *
     * @param node   Expression node
     * @param limit  Number of users required for counting
     * @param offset Starting user index for start counting
     * @return List of paginated set of users.
     * @throws NotImplementedException Not supported filter operation
     * @throws UserStoreException
     */
    private String[] filterUsersUsingLegacyAPIs(ExpressionNode node, int limit, int offset, String domainName)
            throws NotImplementedException, CharonException {

        String[] userNames;

        // Set filter values.
        String attributeName = node.getAttributeValue();
        String filterOperation = node.getOperation();
        String attributeValue = node.getValue();

        // If there is a domain, append the domain with the domain separator in front of the new attribute value if
        // domain separator is not found in the attribute value.
        if (StringUtils.isNotEmpty(domainName) && StringUtils
                .containsNone(attributeValue, CarbonConstants.DOMAIN_SEPARATOR)) {
            attributeValue = domainName.toUpperCase() + CarbonConstants.DOMAIN_SEPARATOR + node.getValue();
        }

        try {
            if (SCIMConstants.UserSchemaConstants.GROUP_URI.equals(attributeName)) {
                if (carbonUM instanceof AbstractUserStoreManager) {
                    String[] roleNames = getRoleNames(attributeName, filterOperation, attributeValue);
                    userNames = getUserListOfRoles(roleNames);
                } else {
                    String errorMessage = String
                            .format("Filter operator %s is not supported by the user store.", filterOperation);
                    throw new NotImplementedException(errorMessage);
                }
            } else {
                // Get the user name of the user with this id.
                userNames = getUserNames(attributeName, filterOperation, attributeValue);
            }
        } catch (UserStoreException e) {
            String errorMessage = String.format("Error while filtering the users for filter with attribute name: %s ,"
                            + " filter operation: %s and attribute value: %s. ", attributeName, filterOperation,
                    attributeValue);
            throw new CharonException(errorMessage, e);
        }
        userNames = paginateUsers(userNames, limit, offset);
        return userNames;
    }

    /**
     * Method to remove duplicate users and get the user details.
     *
     * @param userNames          Filtered user names
     * @param requiredAttributes Required attributes in the response
     * @return Users list with populated attributes
     * @throws CharonException Error in retrieving user details
     */
    private List<Object> getDetailedUsers(String[] userNames, Map<String, Boolean> requiredAttributes)
        throws CharonException {

        List<Object> filteredUsers = new ArrayList<>();
        // 0th index is to store total number of results.
        filteredUsers.add(0);

        // Remove duplicate users.
        HashSet<String> userNamesSet = new HashSet<>(Arrays.asList(userNames));
        userNames = userNamesSet.toArray(new String[0]);

        // Set total number of filtered results.
        filteredUsers.set(0, userNames.length);

        // Get details of the finalized user list.
        filteredUsers.addAll(getFilteredUserDetails(userNames, requiredAttributes));
        return filteredUsers;
    }

    /**
     * This method support multi-attribute filters with paginated search for user(s).
     *
     * @param node
     * @param requiredAttributes
     * @param offset
     * @param limit
     * @param sortBy
     * @param sortOrder
     * @param domainName
     * @param filteredUsers
     * @return
     * @throws CharonException
     */
    private List<Object> getMultiAttributeFilteredUsers(Node node, Map<String, Boolean> requiredAttributes,
                                                        int offset, int limit, String sortBy, String sortOrder,
                                                        String domainName, List<Object> filteredUsers)
            throws CharonException {

        String[] userNames;

        // Handle pagination.
        if (limit > 0) {
            userNames = getFilteredUsersFromMultiAttributeFiltering(node, offset, limit, sortBy,
                    sortOrder, domainName);
            filteredUsers.set(0, userNames.length);
            filteredUsers.addAll(getFilteredUserDetails(userNames, requiredAttributes));
        } else {
            int maxLimit = getMaxLimit();
            if (StringUtils.isNotEmpty(domainName)) {
                userNames = getFilteredUsersFromMultiAttributeFiltering(node, offset, maxLimit, sortBy,
                        sortOrder, domainName);
                filteredUsers.set(0, userNames.length);
                filteredUsers.addAll(getFilteredUserDetails(userNames, requiredAttributes));
            } else {
                int totalUserCount = 0;
                // If pagination and domain name are not given, then perform filtering on all available user stores.
                while (carbonUM != null) {
                    // If carbonUM is not an instance of Abstract User Store Manger we can't get the domain name.
                    if (carbonUM instanceof AbstractUserStoreManager) {
                        domainName = carbonUM.getRealmConfiguration().getUserStoreProperty("DomainName");
                        userNames = getFilteredUsersFromMultiAttributeFiltering(node, offset, maxLimit,
                                sortBy, sortOrder, domainName);
                        totalUserCount += userNames.length;
                        filteredUsers.addAll(getFilteredUserDetails(userNames, requiredAttributes));
                    }
                    carbonUM = carbonUM.getSecondaryUserStoreManager();
                }
                //set the total results
                filteredUsers.set(0, totalUserCount);
            }
        }
        return filteredUsers;
    }

    /**
     * Get maximum user limit to retrieve.
     *
     * @return
     */
    private int getMaxLimit() {

        int givenMax;

        try {
            givenMax = Integer.parseInt(carbonUM.getRealmConfiguration().getUserStoreProperty(
                    "MaxUserNameListLength"));
        } catch (Exception e) {
            givenMax = UserCoreConstants.MAX_USER_ROLE_LIST;
        }

        return givenMax;
    }

    /**
     * Generate condition tree for given filters.
     *
     * @param node
     * @param attributes
     * @return
     * @throws CharonException
     */
    private Condition getCondition(Node node, Map<String, String> attributes) throws CharonException {

        if (node instanceof ExpressionNode) {
            String operation = ((ExpressionNode) node).getOperation();
            String attributeName = ((ExpressionNode) node).getAttributeValue();
            String attributeValue = ((ExpressionNode) node).getValue();

            String conditionOperation;
            String conditionAttributeName;

            if (SCIMCommonConstants.EQ.equals(operation)) {
                conditionOperation = ExpressionOperation.EQ.toString();
            } else if (SCIMCommonConstants.SW.equals(operation)) {
                conditionOperation = ExpressionOperation.SW.toString();
            } else if (SCIMCommonConstants.EW.equals(operation)) {
                conditionOperation = ExpressionOperation.EW.toString();
            } else if (SCIMCommonConstants.CO.equals(operation)) {
                conditionOperation = ExpressionOperation.CO.toString();
            } else {
                conditionOperation = operation;
            }

            if (SCIMConstants.UserSchemaConstants.GROUP_URI.equals(attributeName)) {
                conditionAttributeName = ExpressionAttribute.ROLE.toString();
            } else if (SCIMConstants.UserSchemaConstants.USER_NAME_URI.equals(attributeName)) {
                conditionAttributeName = ExpressionAttribute.USERNAME.toString();
            } else if (attributes.get(attributeName) != null) {
                conditionAttributeName = attributes.get(attributeName);
            } else {
                throw new CharonException("Unsupported attribute: " + attributeName);
            }
            return new ExpressionCondition(conditionOperation, conditionAttributeName, attributeValue);
        } else if (node instanceof OperationNode) {
            Condition leftCondition = getCondition(node.getLeftNode(), attributes);
            Condition rightCondition = getCondition(node.getRightNode(), attributes);
            String operation = ((OperationNode) node).getOperation();
            if (OperationalOperation.AND.toString().equalsIgnoreCase(operation)) {
                return new OperationalCondition(OperationalOperation.AND.toString(), leftCondition, rightCondition);
            } else {
                throw new CharonException("Unsupported Operation: " + operation);
            }
        } else {
            throw new CharonException("Unsupported Operation");
        }
    }

    /**
     * Get all attributes for given domain.
     *
     * @param domainName
     * @return
     * @throws UserStoreException
     */
    private Map<String, String> getAllAttributes(String domainName) throws UserStoreException {

        ClaimMapping[] userClaims;
        ClaimMapping[] coreClaims;
        ClaimMapping[] extensionClaims = null;

        try {
            coreClaims = carbonClaimManager.getAllClaimMappings(SCIMCommonConstants.SCIM_CORE_CLAIM_DIALECT);
            userClaims = carbonClaimManager.getAllClaimMappings(SCIMCommonConstants.SCIM_USER_CLAIM_DIALECT);
            if (SCIMUserSchemaExtensionBuilder.getInstance().getExtensionSchema() != null) {
                extensionClaims = carbonClaimManager.getAllClaimMappings(
                        SCIMUserSchemaExtensionBuilder.getInstance().getExtensionSchema().getURI());
            }
            Map<String, String> attributes = new HashMap<>();
            for (ClaimMapping claim : coreClaims) {
                attributes.put(claim.getClaim().getClaimUri(), claim.getMappedAttribute(domainName));
            }
            for (ClaimMapping claim : userClaims) {
                attributes.put(claim.getClaim().getClaimUri(), claim.getMappedAttribute(domainName));
            }
            if (extensionClaims != null) {
                for (ClaimMapping claim : extensionClaims) {
                    attributes.put(claim.getClaim().getClaimUri(), claim.getMappedAttribute(domainName));
                }
            }
            return attributes;
        } catch (UserStoreException e) {
            throw new UserStoreException("Error in filtering users by multi attributes ", e);
        }
    }

    /**
     * Perform multi attribute filtering.
     *
     * @param node
     * @param offset
     * @param limit
     * @param sortBy
     * @param sortOrder
     * @param domainName
     * @return
     * @throws CharonException
     */
    private String[] getFilteredUsersFromMultiAttributeFiltering(Node node, int offset, int limit, String sortBy,
                                                                 String sortOrder, String domainName)
            throws CharonException {

        String[] userNames;

        try {
            if (StringUtils.isEmpty(domainName)) {
                domainName = "PRIMARY";
            }
            Map<String, String> attributes = getAllAttributes(domainName);
            if (log.isDebugEnabled()) {
                log.debug("Invoking the do get user list for domain: " + domainName);
            }
            userNames = ((PaginatedUserStoreManager) carbonUM).getUserList(getCondition(node, attributes), domainName,
                    UserCoreConstants.DEFAULT_PROFILE, limit, offset, sortBy, sortOrder);
            return userNames;
        } catch (UserStoreException e) {
            throw new CharonException("Error in filtering users by multi attributes ", e);
        }
    }

    /**
     * Get required claim details for filtered user.
     *
     * @param userNames
     * @param requiredAttributes
     * @return
     * @throws CharonException
     */
    private List<Object> getFilteredUserDetails(String[] userNames, Map<String, Boolean> requiredAttributes)
            throws CharonException {

        List<Object> filteredUsers = new ArrayList<>();

        if (userNames == null || userNames.length == 0) {
            if (log.isDebugEnabled()) {
                log.debug("Users for this filter does not exist in the system.");
            }
            return filteredUsers;
        } else {
            try {
                Map<String, String> scimToLocalClaimsMap = SCIMCommonUtils.getSCIMtoLocalMappings();
                List<String> requiredClaims = getOnlyRequiredClaims(scimToLocalClaimsMap.keySet(), requiredAttributes);
                List<String> requiredClaimsInLocalDialect;
                if (MapUtils.isNotEmpty(scimToLocalClaimsMap)) {
                    scimToLocalClaimsMap.keySet().retainAll(requiredClaims);
                    requiredClaimsInLocalDialect = new ArrayList<>(scimToLocalClaimsMap.values());
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("SCIM to Local Claim mappings list is empty.");
                    }
                    requiredClaimsInLocalDialect = new ArrayList<>();
                }

                User[] scimUsers;
                if (isPaginatedUserStoreAvailable()) {
                    if (carbonUM instanceof PaginatedUserStoreManager) {
                        scimUsers = this.getSCIMUsers(userNames, requiredClaimsInLocalDialect, scimToLocalClaimsMap);
                        filteredUsers.addAll(Arrays.asList(scimUsers));
                    } else {
                        addSCIMUsers(filteredUsers, userNames, requiredClaimsInLocalDialect, scimToLocalClaimsMap);
                    }
                } else {
                    addSCIMUsers(filteredUsers, userNames, requiredClaimsInLocalDialect, scimToLocalClaimsMap);
                }
            } catch (UserStoreException e) {
                throw new CharonException("Error in retrieve user details. ", e);
            }
        }
        return filteredUsers;
    }

    private void addSCIMUsers(List<Object> filteredUsers, String[] userNames, List<String> requiredClaims,
                              Map<String, String> scimToLocalClaimsMap)
            throws CharonException {

        User scimUser;
        for (String userName : userNames) {
            if (CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME.equals(userName)) {
                continue;
            }

            scimUser = this.getSCIMUser(userName, requiredClaims, scimToLocalClaimsMap);
            //if SCIM-ID is not present in the attributes, skip
            if (scimUser != null && StringUtils.isBlank(scimUser.getId())) {
                continue;
            }
            filteredUsers.add(scimUser);
        }
    }

    @Override
    public User getMe(String userName,
                      Map<String, Boolean> requiredAttributes) throws CharonException, NotFoundException {

        if (log.isDebugEnabled()) {
            log.debug("Getting user: " + userName);
        }

        User scimUser;

        try {
            //get Claims related to SCIM claim dialect
            Map<String, String> scimToLocalClaimsMap = SCIMCommonUtils.getSCIMtoLocalMappings();
            List<String> requiredClaims = getOnlyRequiredClaims(scimToLocalClaimsMap.keySet(), requiredAttributes);
            List<String> requiredClaimsInLocalDialect;
            if (MapUtils.isNotEmpty(scimToLocalClaimsMap)) {
                scimToLocalClaimsMap.keySet().retainAll(requiredClaims);
                requiredClaimsInLocalDialect = new ArrayList<>(scimToLocalClaimsMap.values());
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("SCIM to Local Claim mappings list is empty.");
                }
                requiredClaimsInLocalDialect = new ArrayList<>();
            }
            //we assume (since id is unique per user) only one user exists for a given id
            scimUser = this.getSCIMUser(userName, requiredClaimsInLocalDialect, scimToLocalClaimsMap);

            if(scimUser == null){
                log.debug("User with userName : " + userName + " does not exist in the system.");
                throw new NotFoundException();
            }else{
                //set the schemas of the scim user
                scimUser.setSchemas();
                log.info("User: " + scimUser.getUserName() + " is retrieved through SCIM.");
                return scimUser;
            }

        } catch (UserStoreException e) {
            throw new CharonException("Error from getting the authenticated user");
        } catch (NotFoundException e) {
            throw new NotFoundException("No such user exist");
        }
    }

    @Override
    public User createMe(User user, Map<String, Boolean> requiredAttributes)
            throws CharonException, ConflictException, BadRequestException {
        return createUser(user, requiredAttributes);
    }

    @Override
    public void deleteMe(String userName) throws NotFoundException, CharonException, NotImplementedException {
        String error = "Self delete is not supported";
        throw new NotImplementedException(error);
    }

    @Override
    public User updateMe(User user, Map<String, Boolean> requiredAttributes)
            throws NotImplementedException, CharonException, BadRequestException {
        return updateUser(user, requiredAttributes);
    }

    @Override
    public Group createGroup(Group group, Map<String, Boolean> requiredAttributes)
            throws CharonException, ConflictException, BadRequestException {
        if (log.isDebugEnabled()) {
            log.debug("Creating group: " + group.getDisplayName());
        }
        try {
            //modify display name if no domain is specified, in order to support multiple user store feature
            String originalName = group.getDisplayName();
            String roleNameWithDomain = null;
            String domainName = "";
            try {
                if (getUserStoreDomainFromSP() != null) {
                    domainName = getUserStoreDomainFromSP();
                    roleNameWithDomain = UserCoreUtil
                            .addDomainToName(UserCoreUtil.removeDomainFromName(originalName), domainName);
                } else if (originalName.indexOf(CarbonConstants.DOMAIN_SEPARATOR) > 0) {
                    domainName = IdentityUtil.extractDomainFromName(originalName);
                    roleNameWithDomain = UserCoreUtil
                            .addDomainToName(UserCoreUtil.removeDomainFromName(originalName), domainName);
                } else {
                    domainName = UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME;
                    roleNameWithDomain = SCIMCommonUtils.getGroupNameWithDomain(originalName);
                }
            } catch (IdentityApplicationManagementException e) {
                throw new CharonException("Error retrieving User Store name. ", e);
            }

            if(!isInternalOrApplicationGroup(domainName) && StringUtils.isNotBlank(domainName) && !isSCIMEnabled
                    (domainName)){
                throw new CharonException("Cannot create group through scim to user store " + ". SCIM is not " +
                        "enabled for user store " + domainName);
            }
            group.setDisplayName(roleNameWithDomain);
            //check if the group already exists
            if (carbonUM.isExistingRole(group.getDisplayName(), false)) {
                String error = "Group with name: " + group.getDisplayName() +" already exists in the system.";
                throw new ConflictException(error);
            }

                /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
            SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);
                /*if members are sent when creating the group, check whether users already exist in the
                user store*/
            List<Object> userIds = group.getMembers();
            List<String> userDisplayNames = group.getMembersWithDisplayName();
            if (CollectionUtils.isNotEmpty(userIds)) {
                List<String> members = new ArrayList<>();
                for (Object userId : userIds) {
                    String userIdLocalClaim = SCIMCommonUtils.getSCIMtoLocalMappings().get(SCIMConstants
                            .CommonSchemaConstants.ID_URI);
                    String[] userNames = null;
                    if (StringUtils.isNotBlank(userIdLocalClaim)) {
                        userNames = carbonUM.getUserList(userIdLocalClaim, (String) userId, UserCoreConstants
                                .DEFAULT_PROFILE);
                    }
                    if (userNames == null || userNames.length == 0) {
                        String error = "User: " + userId + " doesn't exist in the user store. " +
                                "Hence, can not create the group: " + group.getDisplayName();
                        throw new IdentitySCIMException(error);
                    } else if (userNames[0].indexOf(UserCoreConstants.DOMAIN_SEPARATOR) > 0 &&
                            !StringUtils.containsIgnoreCase(userNames[0], domainName)) {
                        String error = "User: " + userId + " doesn't exist in the same user store. " +
                                "Hence, can not create the group: " + group.getDisplayName();
                        throw new IdentitySCIMException(error);
                    } else {
                        members.add(userNames[0]);
                        if (CollectionUtils.isNotEmpty(userDisplayNames)) {
                            boolean userContains = false;
                            for (String user : userDisplayNames) {
                                user =
                                        user.indexOf(UserCoreConstants.DOMAIN_SEPARATOR) > 0
                                                ? user.split(UserCoreConstants.DOMAIN_SEPARATOR)[1]
                                                : user;
                                if (user.equalsIgnoreCase(userNames[0].indexOf(UserCoreConstants.DOMAIN_SEPARATOR) > 0
                                        ? userNames[0].split(UserCoreConstants.DOMAIN_SEPARATOR)[1]
                                        : userNames[0])) {
                                    userContains = true;
                                    break;
                                }
                            }
                            if (!userContains) {
                                throw new IdentitySCIMException("Given SCIM user Id and name does not match..");
                            }
                        }
                    }
                }
                //add other scim attributes in the identity DB since user store doesn't support some attributes.
                SCIMGroupHandler scimGroupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
                scimGroupHandler.createSCIMAttributes(group);
                carbonUM.addRole(group.getDisplayName(),
                        members.toArray(new String[members.size()]), null, false);
                log.info("Group: " + group.getDisplayName() + " is created through SCIM.");
            } else {
                //add other scim attributes in the identity DB since user store doesn't support some attributes.
                SCIMGroupHandler scimGroupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
                scimGroupHandler.createSCIMAttributes(group);
                carbonUM.addRole(group.getDisplayName(), null, null, false);
                log.info("Group: " + group.getDisplayName() + " is created through SCIM.");
            }
        } catch (UserStoreException e) {
            try {
                SCIMGroupHandler scimGroupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
                scimGroupHandler.deleteGroupAttributes(group.getDisplayName());
            } catch (UserStoreException | IdentitySCIMException ex) {
                log.error("Error occurred while doing rollback operation of the SCIM table entry for role: " + group.getDisplayName(), ex);
                throw new CharonException("Error occurred while doing rollback operation of the SCIM table entry for role: " + group.getDisplayName(), e);
            }
            throw new CharonException("Error occurred while adding role : " + group.getDisplayName(), e);
        } catch (IdentitySCIMException | BadRequestException e) {
            String error = "One or more group members do not exist in the same user store. " +
                    "Hence, can not create the group: " + group.getDisplayName();
            throw new BadRequestException(error, ResponseCodeConstants.INVALID_VALUE);
        }
        return group;
    }

    @Override
    public Group getGroup(String id, Map<String, Boolean> requiredAttributes) throws CharonException {
        if (log.isDebugEnabled()) {
            log.debug("Retrieving group with id: " + id);
        }
        Group group = null;
        try {
            SCIMGroupHandler groupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
            //get group name by Id
            String groupName = groupHandler.getGroupName(id);

            if (groupName != null) {
                group = getGroupWithName(groupName);
                group.setSchemas();
                return group;
            } else {
                //returning null will send a resource not found error to client by Charon.
                return null;
            }
        } catch (org.wso2.carbon.user.core.UserStoreException e) {
            throw new CharonException("Error in retrieving group : " + id, e);
        } catch (IdentitySCIMException e) {
            throw new CharonException("Error in retrieving SCIM Group information from database.", e);
        } catch (CharonException | BadRequestException e) {
            throw new CharonException("Error in retrieving the group");
        }
    }

    @Override
    public void deleteGroup(String groupId) throws NotFoundException, CharonException {
        if (log.isDebugEnabled()) {
            log.debug("Deleting group: " + groupId);
        }
        try {
            /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
            SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);

            //get group name by id
            SCIMGroupHandler groupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
            String groupName = groupHandler.getGroupName(groupId);

            if (groupName != null) {
                String userStoreDomainFromSP = null;
                try {
                    userStoreDomainFromSP = getUserStoreDomainFromSP();
                } catch (IdentityApplicationManagementException e) {
                    throw new CharonException("Error retrieving User Store name. ", e);
                }
                if (userStoreDomainFromSP != null &&
                        !(userStoreDomainFromSP.equalsIgnoreCase(IdentityUtil.extractDomainFromName(groupName)))) {
                    throw new CharonException("Group :" + groupName + "is not belong to user store " +
                            userStoreDomainFromSP + "Hence group updating fail");
                }

                String userStoreDomainName = IdentityUtil.extractDomainFromName(groupName);
                if (!isInternalOrApplicationGroup(userStoreDomainName) && StringUtils.isNotBlank(userStoreDomainName)
                        && !isSCIMEnabled
                        (userStoreDomainName)) {
                    throw new CharonException("Cannot delete group: " + groupName + " through scim from user store: " +
                            userStoreDomainName + ". SCIM is not enabled for user store: " + userStoreDomainName);
                }

                //delete group in carbon UM
                carbonUM.deleteRole(groupName);

                //we do not update Identity_SCIM DB here since it is updated in SCIMUserOperationListener's methods.
                log.info("Group: " + groupName + " is deleted through SCIM.");

            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Group with SCIM id: " + groupId + " doesn't exist in the system.");
                }
                throw new NotFoundException();
            }
        } catch (UserStoreException | IdentitySCIMException e) {
            throw new CharonException("Error occurred while deleting group " + groupId, e);
        }

    }

    @Override
    public List<Object> listGroupsWithGET(Node rootNode, int startIndex,
                                          int count, String sortBy, String sortOrder, String domainName,
                                          Map<String, Boolean> requiredAttributes)
            throws CharonException, NotImplementedException, BadRequestException {
        if(sortBy != null || sortOrder != null) {
            throw new NotImplementedException("Sorting is not supported");
        }  else if(startIndex != 1){
            throw new NotImplementedException("Pagination is not supported");
        } else if(rootNode != null) {
            return filterGroups(rootNode, startIndex, count, sortBy, sortOrder, domainName, requiredAttributes);
        } else {
            return listGroups(startIndex, count, sortBy, sortOrder, domainName, requiredAttributes);
        }
    }

    /**
     * List all the groups.
     *
     * @param startIndex         Start index in the request.
     * @param count              Limit in the request.
     * @param sortBy             SortBy
     * @param sortOrder          Sorting order
     * @param domainName         Domain Name
     * @param requiredAttributes Required attributes
     * @return
     * @throws CharonException
     */
    private List<Object> listGroups(int startIndex, int count, String sortBy, String sortOrder, String domainName,
            Map<String, Boolean> requiredAttributes) throws CharonException {

        List<Object> groupList = new ArrayList<>();
        //0th index is to store total number of results;
        groupList.add(0);
        try {
            Set<String> roleNames = getRoleNamesForGroupsEndpoint(domainName);
            for (String roleName : roleNames) {
                String userStoreDomainName = IdentityUtil.extractDomainFromName(roleName);
                if (isInternalOrApplicationGroup(userStoreDomainName) || isSCIMEnabled(userStoreDomainName)) {
                    if (log.isDebugEnabled()) {
                        log.debug("SCIM is enabled for the user-store domain : " + userStoreDomainName + ". "
                                + "Including group with name : " + roleName + " in the response.");
                    }
                    Group group = this.getGroupWithName(roleName);
                    if (group.getId() != null) {
                        groupList.add(group);
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("SCIM is disabled for the user-store domain : " + userStoreDomainName + ". Hence "
                                + "group with name : " + roleName + " is excluded in the response.");
                    }
                }
            }
        } catch (org.wso2.carbon.user.core.UserStoreException e) {
            String errMsg = "Error in obtaining role names from user store.";
            errMsg += e.getMessage();
            throw new CharonException(errMsg, e);
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            String errMsg = "Error in retrieving role names from user store.";
            throw new CharonException(errMsg, e);
        } catch (IdentitySCIMException | BadRequestException e) {
            throw new CharonException("Error in retrieving SCIM Group information from database.", e);
        }
        //set the totalResults value in index 0
        groupList.set(0, groupList.size()-1);
        return groupList;
    }

    /**
     * Get role names according to the given domain. If the domain is not specified, roles of all the user
     * stores will be returned.
     *
     * @param domainName Domain name
     * @return Roles List
     * @throws UserStoreException
     * @throws IdentitySCIMException
     */
    private Set<String> getRoleNamesForGroupsEndpoint(String domainName)
            throws UserStoreException, IdentitySCIMException {

        SCIMGroupHandler groupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
        if (StringUtils.isEmpty(domainName)) {
            return groupHandler.listSCIMRoles();
        } else {
            // If the domain is specified create a attribute value with the domain name.
            String searchValue = domainName + CarbonConstants.DOMAIN_SEPARATOR + SCIMCommonConstants.ANY;

            // Retrieve roles using the above attribute value.
            List<String> roleList = Arrays.asList(((AbstractUserStoreManager) carbonUM)
                    .getRoleNames(searchValue, MAX_ITEM_LIMIT_UNLIMITED, true, true, true));
            Set<String> roleNames = new HashSet<>(roleList);
            return roleNames;
        }
    }

    /**
     * Filter users according to a given filter.
     *
     * @param rootNode           Node
     * @param startIndex         Starting index of the results
     * @param count              Number of required results.
     * @param sortBy             SortBy
     * @param sortOrder          Sorting order
     * @param domainName         Domain name in the request
     * @param requiredAttributes Required attributes
     * @return List of filtered groups
     * @throws NotImplementedException Complex filters are used.
     * @throws CharonException         Unknown node operation.
     */
    private List<Object> filterGroups(Node rootNode, int startIndex, int count, String sortBy, String sortOrder,
            String domainName, Map<String, Boolean> requiredAttributes)
        throws NotImplementedException, CharonException {

        if (rootNode instanceof ExpressionNode) {
            return filterGroupsBySingleAttribute((ExpressionNode) rootNode, startIndex, count, sortBy, sortOrder,
                    domainName, requiredAttributes);
        } else if (rootNode instanceof OperationNode) {
            String error = "Complex filters are not supported yet";
            throw new NotImplementedException(error);
        } else {
            throw new CharonException("Unknown operation. Not either an expression node or an operation node.");
        }
    }

    /**
     * Filter groups with a single attribute.
     *
     * @param node               Expression node
     * @param startIndex         Starting index
     * @param count              Number of results required
     * @param sortBy             SortBy
     * @param sortOrder          Sorting order
     * @param domainName         Domain to be filtered
     * @param requiredAttributes Required attributes
     * @return Filtered groups
     * @throws CharonException Error in Filtering
     */
    private List<Object> filterGroupsBySingleAttribute(ExpressionNode node, int startIndex, int count, String sortBy,
            String sortOrder, String domainName, Map<String, Boolean> requiredAttributes) throws CharonException {

        String attributeName = node.getAttributeValue();
        String filterOperation = node.getOperation();
        String attributeValue = node.getValue();
        if (log.isDebugEnabled()) {
            log.debug("Filtering groups with filter: " + attributeName + " + " + filterOperation + " + "
                    + attributeValue);
        }
        // Check whether the filter operation is supported for filtering in groups.
        if (isFilteringNotSupported(filterOperation)) {
            String errorMessage = "Filter operation: " + filterOperation + " is not supported for groups filtering.";
            throw new CharonException(errorMessage);
        }
        // Resolve the domain name in request according to 'EnableFilteringEnhancements' properties in identity.xml or
        // domain name embedded in the filter attribute value.
        domainName = resolveDomain(domainName, node);
        List<Object> filteredGroups = new ArrayList<>();
        // 0th index is to store total number of results.
        filteredGroups.add(0);
        try {
            String[] roleList = getGroupList(node, domainName);
            if (roleList != null) {
                for (String roleName : roleList) {
                    if (roleName != null && carbonUM.isExistingRole(roleName, false)) {
                        // Skip internal roles.
                        if (CarbonConstants.REGISTRY_ANONNYMOUS_ROLE_NAME.equals(roleName) || UserCoreUtil
                                .isEveryoneRole(roleName, carbonUM.getRealmConfiguration())) {
                            continue;
                        }
                        Group group = getRoleWithDefaultAttributes(roleName);
                        if (group != null && group.getId() != null) {
                            filteredGroups.add(group);
                        }
                    } else {
                        // Returning null will send a resource not found error to client by Charon.
                        filteredGroups.clear();
                        filteredGroups.add(0);
                        return filteredGroups;
                    }
                }
            }
        } catch (org.wso2.carbon.user.core.UserStoreException e) {
            throw new CharonException(
                    "Error in filtering groups by attribute name : " + attributeName + ", " + "attribute value : "
                            + attributeValue + " and filter operation : " + filterOperation, e);
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            throw new CharonException(
                    "Error in filtering group with filter: " + attributeName + " + " + filterOperation + " + "
                            + attributeValue, e);
        }
        // Set the totalResults value in index 0.
        filteredGroups.set(0, filteredGroups.size() - 1);
        return filteredGroups;
    }

    /**
     * Resolve the domain name in request according to 'EnableFilteringEnhancements' properties in identity.xml or
     * domain name embedded in the filter attribute value.
     *
     * @param domainName Domain name passed in the request.
     * @param node       Expression node
     * @return Domain name
     * @throws CharonException
     */
    private String resolveDomain(String domainName, ExpressionNode node) throws CharonException {

        try {
            // Update the domain name if a domain is appended to the attribute value.
            domainName = resolveDomainInAttributeValue(domainName, node);

            // Apply filter enhancements if the domain is not specified in the request.
            if (StringUtils.isEmpty(domainName)) {
                domainName = getDomainWithFilterProperties(node);
            }
            return domainName;
        } catch (BadRequestException e) {
            String errorMessage = String
                    .format(" Domain name in the attribute value: %s does not match with the domain parameter: %s in "
                            + "the request.", node.getValue(), domainName);

            throw new CharonException(errorMessage, e);
        }
    }

    /**
     * Check isFilteringEnhancementsEnabled() which enables filtering in primary domain only.
     *
     * @param node Expression node.
     * @return Primary domain name if properties are enabled or return NULL when properties are disabled.
     */
    private String getDomainWithFilterProperties(ExpressionNode node) {

        if (isFilteringEnhancementsEnabled()) {
            if (SCIMCommonConstants.EQ.equalsIgnoreCase(node.getOperation())) {
                if (StringUtils.equals(node.getAttributeValue(), SCIMConstants.GroupSchemaConstants.DISPLAY_NAME_URI)) {
                    return UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME;
                }
            }
        }
        // Domain value should be returned to indicate no special requirement for primary user store filtering.
        return "";
    }

    /**
     * Resolve domain name if a domain is attached to the attribute value.
     *
     * @param domainName Domain name in the request.
     * @param node       Expression Node.
     * @return Domain name
     */
    private String resolveDomainInAttributeValue(String domainName, ExpressionNode node) throws BadRequestException {

        String attributeName = node.getAttributeValue();
        String attributeValue = node.getValue();
        String extractedDomain;
        if (StringUtils.equals(attributeName, SCIMConstants.GroupSchemaConstants.DISPLAY_NAME_URI) || StringUtils
                .equals(attributeName, SCIMConstants.GroupSchemaConstants.DISPLAY_URI) || StringUtils
                .equals(attributeName, SCIMConstants.GroupSchemaConstants.VALUE_URI)) {

            // Split the attribute value by domain separator. If a domain is embedded in the attribute value, then
            // the size of the array will be 2.
            String[] contentInAttributeValue = attributeValue.split(CarbonConstants.DOMAIN_SEPARATOR, 2);

            // Length less than 1 would indicate that there is no domain appended in front of the attribute value.
            if (contentInAttributeValue.length > 1) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Attribute value: %s is embedded with a domain.", attributeValue));
                }
                extractedDomain = contentInAttributeValue[0].toUpperCase();

                // Check whether the domain name is equal to the extracted domain name from attribute value.
                if (StringUtils.isNotEmpty(domainName) && StringUtils.isNotEmpty(extractedDomain) && !extractedDomain
                        .equalsIgnoreCase(domainName)) {
                    throw new BadRequestException(String.format(
                            " Domain name: %s in the domain parameter does not match with the domain name: %s in "
                                    + "search attribute value of %s claim.", domainName, extractedDomain,
                            attributeName));
                }
                // Remove the domain name from the attribute value and update it in the expression node.
                node.setValue(contentInAttributeValue[1]);
                return extractedDomain;
            } else {
                return domainName;
            }
        } else {
            // If the domain is not embedded, return domain name passed in the request.
            return domainName;
        }
    }

    /**
     * Get the role name with attributes.
     *
     * @param roleName Role name
     * @throws CharonException
     * @throws UserStoreException
     */
    private Group getRoleWithDefaultAttributes(String roleName) throws CharonException, UserStoreException {

        String userStoreDomainName = IdentityUtil.extractDomainFromName(roleName);
        if (isInternalOrApplicationGroup(userStoreDomainName) || isSCIMEnabled(userStoreDomainName)) {
            if (log.isDebugEnabled()) {
                log.debug("SCIM is enabled for the user-store domain : " + userStoreDomainName + ". "
                        + "Including group with name : " + roleName + " in the response.");
            }
            try {
                return getGroupWithName(roleName);
            } catch (IdentitySCIMException e) {
                throw new CharonException("Error in retrieving SCIM Group information from database.", e);
            } catch (BadRequestException e) {
                throw new CharonException("Error in retrieving SCIM Group.", e);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("SCIM is disabled for the user-store domain : " + userStoreDomainName + ". Hence "
                        + "group with name : " + roleName + " is excluded in the response.");
            }
            // Return NULL implies that a group cannot be created.
            return null;
        }
    }

    @Override
    public Group updateGroup(Group oldGroup, Group newGroup, Map<String, Boolean> requiredAttributes)
            throws CharonException {

        try {
            String userStoreDomainFromSP = getUserStoreDomainFromSP();

            if(userStoreDomainFromSP != null && !userStoreDomainFromSP.equalsIgnoreCase(
                    IdentityUtil.extractDomainFromName(oldGroup.getDisplayName()))) {
                throw new CharonException("Group :" + oldGroup.getDisplayName() + "is not belong to user store " +
                        userStoreDomainFromSP + "Hence group updating fail");
            }
            oldGroup.setDisplayName(UserCoreUtil.addDomainToName(UserCoreUtil.removeDomainFromName(oldGroup.getDisplayName()),
                    IdentityUtil.extractDomainFromName(oldGroup.getDisplayName())));

            newGroup.setDisplayName(UserCoreUtil.addDomainToName(UserCoreUtil.removeDomainFromName(newGroup.getDisplayName()),
                    IdentityUtil.extractDomainFromName(newGroup.getDisplayName())));

            String primaryDomain = IdentityUtil.getPrimaryDomainName();
            if (IdentityUtil.extractDomainFromName(newGroup.getDisplayName()).equals(primaryDomain) && !(IdentityUtil
                    .extractDomainFromName(oldGroup.getDisplayName())
                    .equals(primaryDomain))) {
                String userStoreDomain = IdentityUtil.extractDomainFromName(oldGroup.getDisplayName());
                newGroup.setDisplayName(UserCoreUtil.addDomainToName(newGroup.getDisplayName(), userStoreDomain));

            } else if (!IdentityUtil.extractDomainFromName(oldGroup.getDisplayName())
                    .equals(IdentityUtil.extractDomainFromName(newGroup.getDisplayName()))) {
                throw new IdentitySCIMException(
                        "User store domain of the group is not matching with the given SCIM group Id.");
            }

            newGroup.setDisplayName(SCIMCommonUtils.getGroupNameWithDomain(newGroup.getDisplayName()));
            oldGroup.setDisplayName(SCIMCommonUtils.getGroupNameWithDomain(oldGroup.getDisplayName()));

            if (log.isDebugEnabled()) {
                log.debug("Updating group: " + oldGroup.getDisplayName());
            }

            String groupName = newGroup.getDisplayName();
            String userStoreDomainForGroup = IdentityUtil.extractDomainFromName(groupName);

            if (newGroup.getMembers() != null && !(newGroup.getMembers().isEmpty()) &&
                    !isInternalOrApplicationGroup(userStoreDomainForGroup)) {
                newGroup = addDomainToUserMembers(newGroup, userStoreDomainForGroup);
            }
            boolean updated = false;
                /*set thread local property to signal the downstream SCIMUserOperationListener
                about the provisioning route.*/
            SCIMCommonUtils.setThreadLocalIsManagedThroughSCIMEP(true);

            // Find out added user's user ids as a list.
            List<Object> newlyAddedUserIds = newGroup.getMembers();
            List<Object> oldGroupUserIds = oldGroup.getMembers();
            if (oldGroupUserIds != null && oldGroupUserIds.size() > 0) {
                newlyAddedUserIds.removeAll(oldGroup.getMembers());
            }
            // Find out added members and deleted members..
            List<String> addedMembers = new ArrayList<>();
            List<String> deletedMembers = new ArrayList<>();

            List<String> oldMembers = oldGroup.getMembersWithDisplayName();
            List<String> newMembers = newGroup.getMembersWithDisplayName();
            if (newMembers != null) {
                //check for deleted members
                if (CollectionUtils.isNotEmpty(oldMembers)) {
                    for (String oldMember : oldMembers) {
                        if (newMembers != null && newMembers.contains(oldMember)) {
                            continue;
                        }
                        deletedMembers.add(oldMember);
                    }
                }

                //check for added members
                if (CollectionUtils.isNotEmpty(newMembers)) {
                    for (String newMember : newMembers) {
                        if (oldMembers != null && oldMembers.contains(newMember)) {
                            continue;
                        }
                        doUserValidation(newMember, userStoreDomainForGroup, oldGroup.getDisplayName(),
                                newlyAddedUserIds);
                        addedMembers.add(newMember);
                    }
                }
            }

            // We do not update Identity_SCIM DB here since it is updated in SCIMUserOperationListener's methods.

            // Update name if it is changed.
            if (!(oldGroup.getDisplayName().equalsIgnoreCase(newGroup.getDisplayName()))) {
                // Update group name in carbon UM.
                carbonUM.updateRoleName(oldGroup.getDisplayName(),
                        newGroup.getDisplayName());
                updated = true;
            }

            if (CollectionUtils.isNotEmpty(addedMembers) || CollectionUtils.isNotEmpty(deletedMembers)) {
                carbonUM.updateUserListOfRole(newGroup.getDisplayName(),
                        deletedMembers.toArray(new String[deletedMembers.size()]),
                        addedMembers.toArray(new String[addedMembers.size()]));
                updated = true;
            }

            if (updated) {
                log.info("Group: " + newGroup.getDisplayName() + " is updated through SCIM.");
                // In case the duplicate existing in the newGroup, query the corresponding group
                // again and return it.
                Group newUpdatedGroup = getGroup(newGroup.getId(), requiredAttributes);
                return newUpdatedGroup;
            } else {
                log.warn("There is no updated field in the group: " + oldGroup.getDisplayName() +
                        ". Therefore ignoring the provisioning.");
                // Hence no changes were done, return original group. There are some cases, new group can have
                // duplicated members.
                return oldGroup;
            }

        } catch (UserStoreException | IdentitySCIMException e) {
            throw new CharonException(e.getMessage(), e);
        } catch (IdentityApplicationManagementException e){
            throw new CharonException("Error retrieving User Store name. ", e);
        } catch (BadRequestException | CharonException e) {
            throw new CharonException("Error in updating the group", e);

        }
    }

    /**
     * Perform user validation, check provided added member(s) details are exists in the user store. Else throw
     * corresponding error.
     *
     * @param userName
     * @param userStoreDomainForGroup
     * @param displayName
     * @param addedUserIdsList
     * @throws IdentitySCIMException
     * @throws org.wso2.carbon.user.core.UserStoreException
     */
    private void doUserValidation(String userName, String userStoreDomainForGroup, String displayName,
            List<Object> addedUserIdsList) throws IdentitySCIMException, org.wso2.carbon.user.core.UserStoreException {

        // Compare user store domain of group and user store domain of user name, if there is a mismatch do not
        // update the group.
        String userStoreDomainForUser = IdentityUtil.extractDomainFromName(userName);
        if (!isInternalOrApplicationGroup(userStoreDomainForGroup) && !userStoreDomainForGroup
                .equalsIgnoreCase(userStoreDomainForUser)) {
            throw new IdentitySCIMException(userName + " does not belongs to user store " + userStoreDomainForGroup);
        }

        // Check if the user ids & associated user name sent in updated (new) group exist in the user store.
        if (userName != null) {
            String userId = carbonUM.getUserClaimValue(userName, SCIMConstants.CommonSchemaConstants.ID_URI, null);
            if (userId == null || userId.isEmpty()) {
                String error = "User: " + userName + " doesn't exist in the user store. Hence, can not update the "
                        + "group: " + displayName;
                throw new IdentitySCIMException(error);
            } else {
                if (!UserCoreUtil.isContain(userId, addedUserIdsList.toArray(new String[addedUserIdsList.size()]))) {
                    throw new IdentitySCIMException("Given SCIM user Id and name not matching..");
                }
            }
        }
    }

    @Override
    public List<Object> listGroupsWithPost(SearchRequest searchRequest, Map<String, Boolean> requiredAttributes)
            throws BadRequestException, NotImplementedException, CharonException {
        return listGroupsWithGET(searchRequest.getFilter(), searchRequest.getStartIndex(), searchRequest.getCount(),
                searchRequest.getSortBy(), searchRequest.getSortOder(), searchRequest.getDomainName(),
                requiredAttributes);
    }


    private String getUserStoreDomainFromSP() throws IdentityApplicationManagementException {
        ServiceProvider serviceProvider = null;

        if (serviceProvider != null && serviceProvider.getInboundProvisioningConfig() != null &&
                !StringUtils.isBlank(serviceProvider.getInboundProvisioningConfig().getProvisioningUserStore())) {
            return serviceProvider.getInboundProvisioningConfig().getProvisioningUserStore();
        }
        return null;
    }

    /**
     * This method will return whether SCIM is enabled or not for a particular userStore. (from SCIMEnabled user
     * store property)
     * @param userStoreName user store name
     * @return whether scim is enabled or not for the particular user store
     */
    private boolean isSCIMEnabled(String userStoreName) {
        UserStoreManager userStoreManager = carbonUM.getSecondaryUserStoreManager(userStoreName);
        if (userStoreManager != null) {
            try {
                return userStoreManager.isSCIMEnabled();
            } catch (UserStoreException e) {
                log.error("Error while evaluating isSCIMEnalbed for user store " + userStoreName, e);
            }
        }
        return false;
    }

    /**
     * get the specfied user from the store
     * @param userName
     * @param claimURIList
     * @return
     * @throws CharonException
     */
    private User getSCIMUser(String userName, List<String> claimURIList, Map<String, String> scimToLocalClaimsMap)
            throws CharonException {
        User scimUser = null;

        String userStoreDomainName = IdentityUtil.extractDomainFromName(userName);
        if(StringUtils.isNotBlank(userStoreDomainName) && !isSCIMEnabled(userStoreDomainName)){
            throw new CharonException("Cannot get user through scim to user store " + ". SCIM is not " +
                    "enabled for user store " + userStoreDomainName);
        }
        try {
            //obtain user claim values
            Map<String, String> userClaimValues = carbonUM.getUserClaimValues(
                    userName, claimURIList.toArray(new String[claimURIList.size()]), null);
            Map<String, String> attributes = SCIMCommonUtils.convertLocalToSCIMDialect(userClaimValues,
                    scimToLocalClaimsMap);

            //skip simple type addresses claim because it is complex with sub types in the schema
            if (attributes.containsKey(SCIMConstants.UserSchemaConstants.ADDRESSES_URI)) {
                attributes.remove(SCIMConstants.UserSchemaConstants.ADDRESSES_URI);
            }

            // Add username with domain name
            attributes.put(SCIMConstants.UserSchemaConstants.USER_NAME_URI, userName);

            //get groups of user and add it as groups attribute
            String[] roles = carbonUM.getRoleListOfUser(userName);
            //construct the SCIM Object from the attributes
            scimUser = (User) AttributeMapper.constructSCIMObjectFromAttributes(attributes, 1);

            Map<String, Group> groupMetaAttributesCache = new HashMap<>();
            //add groups of user:
            for (String role : roles) {
                if (UserCoreUtil.isEveryoneRole(role, carbonUM.getRealmConfiguration())
                        || CarbonConstants.REGISTRY_ANONNYMOUS_ROLE_NAME.equalsIgnoreCase(role)
                        || role.toLowerCase().startsWith((UserCoreConstants.INTERNAL_DOMAIN +
                        CarbonConstants.DOMAIN_SEPARATOR).toLowerCase())) {
                    // carbon specific roles do not possess SCIM info, hence
                    // skipping them.
                    // skip intenal roles
                    continue;
                }

                if (SCIMCommonUtils.isFilteringEnhancementsEnabled()) {
                    if (!StringUtils.contains(role, CarbonConstants.DOMAIN_SEPARATOR)) {
                        role = UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME + CarbonConstants.DOMAIN_SEPARATOR + role;
                    }
                }
                Group group = groupMetaAttributesCache.get(role);
                if (group == null && !groupMetaAttributesCache.containsKey(role)) {
                    group = getGroupOnlyWithMetaAttributes(role);
                    groupMetaAttributesCache.put(role, group);
                }

                if (group != null) { // can be null for non SCIM groups
                    scimUser.setGroup(null, group.getId(), role);
                }
            }
        } catch (UserStoreException | CharonException | NotFoundException | IdentitySCIMException |BadRequestException e) {
            throw new CharonException("Error in getting user information for user: " + userName, e);
        }
        return scimUser;
    }

    /**
     * get the specified user from the store
     *
     * @param userNames    Array of usernames
     * @param claimURIList Requested claim list
     * @return Array of SCIM User
     * @throws CharonException CharonException
     */
    private User[] getSCIMUsers(String[] userNames, List<String> claimURIList, Map<String, String>
            scimToLocalClaimsMap) throws CharonException {

        List<User> scimUsers = new ArrayList<>();

        //obtain user claim values
        UserClaimSearchEntry[] searchEntries;
        Map<String, List<String>> usersRoles;

        try {
            searchEntries = ((AbstractUserStoreManager) carbonUM).getUsersClaimValues(
                    userNames, claimURIList.toArray(new String[claimURIList.size()]), null);

            usersRoles = ((AbstractUserStoreManager) carbonUM).getRoleListOfUsers(userNames);
        } catch (org.wso2.carbon.user.core.UserStoreException e) {
            throw new CharonException("Error occurred while retrieving SCIM user information", e);
        }
        Map<String, Group> groupMetaAttributesCache = new HashMap<>();

        for (String userName : userNames) {
            User scimUser;
            Map<String, String> userClaimValues = new HashMap<>();
            for (UserClaimSearchEntry entry : searchEntries) {
                if (StringUtils.isNotBlank(entry.getUserName()) && entry.getUserName().equals(userName)) {
                    userClaimValues = entry.getClaims();
                }
            }
            Map<String, String> attributes;
            try {
                attributes = SCIMCommonUtils.convertLocalToSCIMDialect(userClaimValues, scimToLocalClaimsMap);
            } catch (UserStoreException e) {
                throw new CharonException("Error in converting local claims to SCIM dialect for user: " + userName, e);
            }

            String userStoreDomainName = IdentityUtil.extractDomainFromName(userName);
            if (StringUtils.isNotBlank(userStoreDomainName) && !isSCIMEnabled(userStoreDomainName)) {
                throw new CharonException("Cannot get user through scim to user store " + ". SCIM is not " +
                        "enabled for user store " + userStoreDomainName);
            }

            try {
                //skip simple type addresses claim because it is complex with sub types in the schema
                if (attributes.containsKey(SCIMConstants.UserSchemaConstants.ADDRESSES_URI)) {
                    attributes.remove(SCIMConstants.UserSchemaConstants.ADDRESSES_URI);
                }

                // Add username with domain name
                attributes.put(SCIMConstants.UserSchemaConstants.USER_NAME_URI, userName);

                //get groups of user and add it as groups attribute
                List<String> roleList = usersRoles.get(userName);
                String[] roles = new String[0];
                if (CollectionUtils.isNotEmpty(roleList)) {
                    roles = roleList.toArray(new String[0]);
                }

                //construct the SCIM Object from the attributes
                scimUser = (User) AttributeMapper.constructSCIMObjectFromAttributes(attributes, 1);

                //add groups of user
                for (String role : roles) {
                    if (UserCoreUtil.isEveryoneRole(role, carbonUM.getRealmConfiguration())
                            || CarbonConstants.REGISTRY_ANONNYMOUS_ROLE_NAME.equalsIgnoreCase(role)
                            || role.toLowerCase().startsWith((UserCoreConstants.INTERNAL_DOMAIN +
                            CarbonConstants.DOMAIN_SEPARATOR).toLowerCase())) {
                        // carbon specific roles do not possess SCIM info, hence
                        // skipping them.
                        // skip internal roles
                        continue;
                    }

                    if (SCIMCommonUtils.isFilteringEnhancementsEnabled()) {
                        if (!StringUtils.contains(role, CarbonConstants.DOMAIN_SEPARATOR)) {
                            role = UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME + CarbonConstants.DOMAIN_SEPARATOR + role;
                        }
                    }
                    Group group = groupMetaAttributesCache.get(role);
                    if (group == null && !groupMetaAttributesCache.containsKey(role)) {
                        group = getGroupOnlyWithMetaAttributes(role);
                        groupMetaAttributesCache.put(role, group);
                    }

                    if (group != null) { // can be null for non SCIM groups
                        scimUser.setGroup(null, group.getId(), role);
                    }
                }
            } catch (UserStoreException | CharonException | NotFoundException | IdentitySCIMException | BadRequestException e) {
                throw new CharonException("Error in getting user information for user: " + userName, e);
            }

            if (scimUser != null) {
                scimUsers.add(scimUser);
            }
        }
        return scimUsers.toArray(new User[0]);
    }

    /**
     * Get group with only meta attributes.
     *
     * @param groupName
     * @return
     * @throws CharonException
     * @throws IdentitySCIMException
     * @throws org.wso2.carbon.user.core.UserStoreException
     */
    private Group getGroupOnlyWithMetaAttributes(String groupName) throws CharonException, IdentitySCIMException,
            org.wso2.carbon.user.core.UserStoreException, BadRequestException {
        //get other group attributes and set.
        Group group = new Group();
        group.setDisplayName(groupName);
        SCIMGroupHandler groupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
        return groupHandler.getGroupWithAttributes(group, groupName);
    }

    /**
     * returns whether particular user store domain is application or internal.
     * @param userstoreDomain user store domain
     * @return whether passed domain name is "internal" or "application"
     */
    private boolean isInternalOrApplicationGroup(String userstoreDomain){
        if(StringUtils.isNotBlank(userstoreDomain) &&
                (SCIMCommonConstants.APPLICATION_DOMAIN.equalsIgnoreCase(userstoreDomain) ||
                        SCIMCommonConstants.INTERNAL_DOMAIN.equalsIgnoreCase(userstoreDomain))){
            return true;
        }
        return false;
    }

    /**
     * Get the full group with all the details including users.
     *
     * @param groupName
     * @return
     * @throws CharonException
     * @throws org.wso2.carbon.user.core.UserStoreException
     * @throws IdentitySCIMException
     */
    private Group getGroupWithName(String groupName)
            throws CharonException, org.wso2.carbon.user.core.UserStoreException, IdentitySCIMException, BadRequestException {

        String userStoreDomainName = IdentityUtil.extractDomainFromName(groupName);
        if(!isInternalOrApplicationGroup(userStoreDomainName) && StringUtils.isNotBlank(userStoreDomainName) &&
                !isSCIMEnabled(userStoreDomainName)){
            throw new CharonException("Cannot retrieve group through scim to user store " + ". SCIM is not " +
                    "enabled for user store " + userStoreDomainName);
        }

        Group group = new Group();
        group.setDisplayName(groupName);
        String[] userNames = carbonUM.getUserListOfRole(groupName);

        //get the ids of the users and set them in the group with id + display name
        if (userNames != null && userNames.length != 0) {
            for (String userName : userNames) {
                String userId = carbonUM.getUserClaimValue(userName, SCIMConstants.CommonSchemaConstants.ID_URI, null);
                group.setMember(userId, userName);
            }
        }
        //get other group attributes and set.
        SCIMGroupHandler groupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
        group = groupHandler.getGroupWithAttributes(group, groupName);
        return group;
    }

    /**
     * This is used to add domain name to the members of a group
     *
     * @param group
     * @param userStoreDomain
     * @return
     * @throws CharonException
     */
    private Group addDomainToUserMembers(Group group, String userStoreDomain) throws CharonException {
        List<Object> membersId = group.getMembers();

        if (StringUtils.isBlank(userStoreDomain) || membersId == null || membersId.isEmpty()) {
            return group;
        }

        if (group.isAttributeExist(SCIMConstants.GroupSchemaConstants.MEMBERS)) {
            MultiValuedAttribute members = (MultiValuedAttribute) group.getAttributeList().get(
                    SCIMConstants.GroupSchemaConstants.MEMBERS);
            List<Attribute> attributeValues = members.getAttributeValues();

            if (attributeValues != null && !attributeValues.isEmpty()) {
                for (Attribute attributeValue : attributeValues) {
                    SimpleAttribute displayNameAttribute = (SimpleAttribute) attributeValue.getSubAttribute(
                            SCIMConstants.CommonSchemaConstants.DISPLAY);
                    String displayName =
                            AttributeUtil.getStringValueOfAttribute(displayNameAttribute.getValue(),
                                    displayNameAttribute.getType());
                    displayNameAttribute.setValue(UserCoreUtil.addDomainToName(
                            UserCoreUtil.removeDomainFromName(displayName), userStoreDomain));
                }
            }
        }
        return group;
    }

    private List<String> getMappedClaimList(Map<String, Boolean> requiredAttributes){
        ArrayList<String> claimsList = new ArrayList<>();

        for(Map.Entry<String, Boolean> claim : requiredAttributes.entrySet()){
            if(claim.getValue().equals(true)){


            } else {
                claimsList.add(claim.getKey());
            }
        }


        return claimsList;
    }

    /*
     * This returns the only required attributes for value querying
     * @param claimURIList
     * @param requiredAttributes
     * @return
     */

    private List<String> getOnlyRequiredClaims(Set<String> claimURIList, Map<String, Boolean> requiredAttributes) {
        List<String> requiredClaimList = new ArrayList<>();
        for(String requiredClaim : requiredAttributes.keySet()) {
            if(requiredAttributes.get(requiredClaim)) {
                if (claimURIList.contains(requiredClaim)) {
                    requiredClaimList.add(requiredClaim);
                } else {
                    String[] parts = requiredClaim.split("[.]");
                    for (String claim : claimURIList) {
                        if (parts.length == 3) {
                            if (claim.contains(parts[0] +"." + parts[1])) {
                                if (!requiredClaimList.contains(claim)) {
                                    requiredClaimList.add(claim);
                                }
                            }
                        } else if (parts.length == 2) {
                            if (claim.contains(parts[0])) {
                                if (!requiredClaimList.contains(claim)) {
                                    requiredClaimList.add(claim);
                                }
                            }
                        }

                    }
                }
            } else {
                if (!requiredClaimList.contains(requiredClaim)) {
                    requiredClaimList.add(requiredClaim);
                }
            }
        }
        return requiredClaimList;
    }
    private String[] paginateUsers(String[] users, int limit, int offset) {

        Arrays.sort(users);

        if (offset <= 0) {
            offset = 1;
        }

        if (limit <= 0) {
            // This is to support backward compatibility.
            return users;
        }

        if (users == null) {
            return new String[0];
        } else if (offset > users.length) {
            return new String[0];
        } else if (users.length < limit + offset) {
            limit = users.length - offset + 1;
            return Arrays.copyOfRange(users, offset - 1, limit + offset - 1);
        } else {
            return Arrays.copyOfRange(users, offset - 1, limit + offset - 1);
        }
    }

    /**
     * check whether the filtering is supported.
     *
     * @param filterOperation Operator to be used for filtering
     * @return boolean to check whether operator is supported
     */
    private boolean isFilteringNotSupported(String filterOperation) {

        return !filterOperation.equalsIgnoreCase(SCIMCommonConstants.EQ)
                && !filterOperation.equalsIgnoreCase(SCIMCommonConstants.CO)
                && !filterOperation.equalsIgnoreCase(SCIMCommonConstants.SW)
                && !filterOperation.equalsIgnoreCase(SCIMCommonConstants.EW);
    }

    private String[] getUserListOfRoles(String[] roleNames) throws org.wso2.carbon.user.core.UserStoreException {

        String[] userNames;
        Set<String> users = new HashSet<>();
        if (roleNames != null) {
            for (String roleName : roleNames) {
                users.addAll(Arrays.asList(carbonUM.getUserListOfRole(roleName)));
            }
        }
        userNames = users.toArray(new String[0]);
        return userNames;
    }

    /**
     * Get the search value after appending the delimiters according to the attribute name to be filtered.
     *
     * @param attributeName   Filter attribute name
     * @param filterOperation Operator value
     * @param attributeValue  Search value
     * @param delimiter       Filter delimiter based on search type
     * @return Search attribute
     */
    private String getSearchAttribute(String attributeName, String filterOperation, String attributeValue,
            String delimiter) {

        String searchAttribute = null;
        if (filterOperation.equalsIgnoreCase(SCIMCommonConstants.CO)) {
            searchAttribute = createSearchValueForCoOperation(attributeName, filterOperation, attributeValue,
                    delimiter);
        } else if (filterOperation.equalsIgnoreCase(SCIMCommonConstants.SW)) {
            searchAttribute = attributeValue + delimiter;
        } else if (filterOperation.equalsIgnoreCase(SCIMCommonConstants.EW)) {
            searchAttribute = createSearchValueForEwOperation(attributeName, filterOperation, attributeValue,
                    delimiter);
        } else if (filterOperation.equalsIgnoreCase(SCIMCommonConstants.EQ)) {
            searchAttribute = attributeValue;
        }
        return searchAttribute;
    }

    /**
     * Create search value for CO operation.
     *
     * @param attributeName   Filter attribute name
     * @param filterOperation Operator value
     * @param attributeValue  Filter attribute value
     * @param delimiter       Filter delimiter based on search type
     * @return Search attribute value
     */
    private String createSearchValueForCoOperation(String attributeName, String filterOperation, String attributeValue,
            String delimiter) {

        // For attributes which support domain embedding, create search value by appending the delimiter after
        // the domain separator.
        if (isDomainSupportedAttribute(attributeName)) {

            // Check whether domain is embedded in the attribute value.
            String[] attributeItems = attributeValue.split(CarbonConstants.DOMAIN_SEPARATOR, 2);
            if (attributeItems.length > 1) {
                return createSearchValueWithDomainForCoEwOperations(attributeName, filterOperation, attributeValue,
                        delimiter, attributeItems);
            } else {
                return delimiter + attributeValue + delimiter;
            }
        } else {
            return delimiter + attributeValue + delimiter;
        }
    }

    /**
     * Create search value for EW operation.
     *
     * @param attributeName   Filter attribute name
     * @param filterOperation Operator value
     * @param attributeValue  Filter attribute value
     * @param delimiter       Filter delimiter based on search type
     * @return Search attribute value
     */
    private String createSearchValueForEwOperation(String attributeName, String filterOperation, String attributeValue,
            String delimiter) {

        // For attributes which support domain embedding, create search value by appending the delimiter after
        // the domain separator.
        if (isDomainSupportedAttribute(attributeName)) {
            // Extract the domain attached to the attribute value and then append the delimiter.
            String[] attributeItems = attributeValue.split(CarbonConstants.DOMAIN_SEPARATOR, 2);
            if (attributeItems.length > 1) {
                return createSearchValueWithDomainForCoEwOperations(attributeName, filterOperation, attributeValue,
                        delimiter, attributeItems);
            } else {
                return delimiter + attributeValue;
            }
        } else {
            return delimiter + attributeValue;
        }
    }

    /**
     * Create search value for CO and EW operations when domain is detected in the filter attribute value.
     *
     * @param attributeName   Filter attribute name
     * @param filterOperation Operator value
     * @param attributeValue  Search value
     * @param delimiter       Filter delimiter based on search type
     * @param attributeItems  Extracted domain and filter value
     * @return Search attribute value
     */
    private String createSearchValueWithDomainForCoEwOperations(String attributeName, String filterOperation,
            String attributeValue, String delimiter, String[] attributeItems) {

        String searchAttribute;
        if (log.isDebugEnabled()) {
            log.debug(String.format(
                    "Domain detected in attribute value: %s for filter attribute: %s for " + "filter operation; %s.",
                    attributeValue, attributeName, filterOperation));
        }
        if (filterOperation.equalsIgnoreCase(SCIMCommonConstants.EW)) {
            searchAttribute = attributeItems[0] + CarbonConstants.DOMAIN_SEPARATOR + delimiter + attributeItems[1];
        } else if (filterOperation.equalsIgnoreCase(SCIMCommonConstants.CO)) {
            searchAttribute =
                    attributeItems[0] + CarbonConstants.DOMAIN_SEPARATOR + delimiter + attributeItems[1] + delimiter;
        } else {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Filter operation: %s is not supported by method "
                        + "createSearchValueWithDomainForCoEwOperations to create a search value."));
            }
            searchAttribute = attributeValue;
        }
        if (log.isDebugEnabled()) {
            log.debug(
                    String.format("Search attribute value : %s is created for operation: %s created with domain : %s ",
                            searchAttribute, filterOperation, attributeItems[0]));
        }
        return searchAttribute;
    }

    /**
     * Check whether the filter attribute support filtering with the domain embedded in the attribute value.
     *
     * @param attributeName Attribute to filter
     * @return True if the given attribute support embedding domain in attribute value.
     */
    private boolean isDomainSupportedAttribute(String attributeName) {

        return SCIMConstants.UserSchemaConstants.USER_NAME_URI.equalsIgnoreCase(attributeName)
                || SCIMConstants.CommonSchemaConstants.ID_URI.equalsIgnoreCase(attributeName)
                || SCIMConstants.UserSchemaConstants.GROUP_URI.equalsIgnoreCase(attributeName)
                || SCIMConstants.GroupSchemaConstants.DISPLAY_NAME_URI.equalsIgnoreCase(attributeName)
                || SCIMConstants.GroupSchemaConstants.DISPLAY_URI.equalsIgnoreCase(attributeName);
    }

    /**
     * Get list of roles that matches the search criteria.
     *
     * @param attributeName   Filter attribute name
     * @param filterOperation Operator value
     * @param attributeValue  Search value
     * @return List of role names
     * @throws org.wso2.carbon.user.core.UserStoreException Error getting roleNames.
     */
    private String[] getRoleNames(String attributeName, String filterOperation, String attributeValue)
            throws org.wso2.carbon.user.core.UserStoreException {

        String searchAttribute = getSearchAttribute(attributeName, filterOperation, attributeValue,
                FILTERING_DELIMITER);
        if (log.isDebugEnabled()) {
            log.debug(String.format("Filtering roleNames from search attribute: %s", searchAttribute));
        }
        return ((AbstractUserStoreManager) carbonUM)
                .getRoleNames(searchAttribute, MAX_ITEM_LIMIT_UNLIMITED, true, true, true);
    }

    /**
     * Get list of user that matches the search criteria.
     *
     * @param attributeName   Field name for search
     * @param filterOperation Operator
     * @param attributeValue  Search value
     * @return List of users
     * @throws org.wso2.carbon.user.core.UserStoreException
     */
    private String[] getUserNames(String attributeName, String filterOperation, String attributeValue)
            throws org.wso2.carbon.user.core.UserStoreException {

        String searchAttribute = getSearchAttribute(attributeName, filterOperation, attributeValue,
                FILTERING_DELIMITER);

        // Convert attribute name to the local claim dialect if it's mapped to an identity claim. Identity
        // claims are persisted in the IdentityDataStore in the local claim dialect, and does not support searching
        // users with claim URIs in SCIM dialect.
        String attributeNameInLocalDialect = SCIMCommonUtils.convertSCIMtoLocalDialect(attributeName);
        if (attributeNameInLocalDialect.contains(UserCoreConstants.ClaimTypeURIs.IDENTITY_CLAIM_URI)) {
            return carbonUM.getUserList(attributeNameInLocalDialect, searchAttribute,
                    UserCoreConstants.DEFAULT_PROFILE);
        }
        if (log.isDebugEnabled()) {
            log.debug(String.format("Filtering userNames from search attribute: %s", searchAttribute));
        }
        return carbonUM.getUserList(attributeName, searchAttribute, UserCoreConstants.DEFAULT_PROFILE);
    }

    /**
     * Get the list of groups that matches the search criteria.
     *
     * @param expressionNode Expression node for the filter.
     * @param domainName     Domain name
     * @return List of user groups
     * @throws org.wso2.carbon.user.core.UserStoreException
     * @throws IdentitySCIMException
     */
    private String[] getGroupList(ExpressionNode expressionNode, String domainName)
            throws org.wso2.carbon.user.core.UserStoreException, CharonException {

        String attributeName = expressionNode.getAttributeValue();
        String filterOperation = expressionNode.getOperation();
        String attributeValue = expressionNode.getValue();

        // Groups endpoint only support display uri and value uri.
        if (attributeName.equals(SCIMConstants.GroupSchemaConstants.DISPLAY_URI) || attributeName
                .equals(SCIMConstants.GroupSchemaConstants.VALUE_URI)) {
            String[] userList;

            // Update attribute value with the domain name.
            attributeValue = prependDomainNameToTheAttributeValue(attributeValue, domainName);

            // Listing users.
            if (attributeName.equals(SCIMConstants.GroupSchemaConstants.DISPLAY_URI)) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Filter attribute: %s mapped to filter attribute: %s to filter users in "
                            + "groups endpoint.", attributeName, SCIMConstants.UserSchemaConstants.USER_NAME_URI));
                }
                userList = getUserNames(SCIMConstants.UserSchemaConstants.USER_NAME_URI, filterOperation,
                        attributeValue);
            } else {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Filter attribute: %s mapped to filter attribute: %s to filter users in "
                            + "groups endpoint", attributeName, SCIMConstants.CommonSchemaConstants.ID_URI));
                }
                userList = getUserNames(SCIMConstants.CommonSchemaConstants.ID_URI, filterOperation, attributeValue);
            }
            // Get the roles of the users.
            Set<String> fullRoleList = new HashSet<>();
            if (userList != null) {
                for (String userName : userList) {
                    fullRoleList.addAll(Arrays.asList(carbonUM.getRoleListOfUser(userName)));
                }
            }
            return fullRoleList.toArray(new String[0]);
        } else if (attributeName.equals(SCIMConstants.GroupSchemaConstants.DISPLAY_NAME_URI)) {
            attributeValue = prependDomainNameToTheAttributeValue(attributeValue, domainName);
            return getRoleNames(attributeName, filterOperation, attributeValue);
        } else {
            try {
                return getGroupNamesFromDB(attributeName, filterOperation, attributeValue, domainName);
            } catch (IdentitySCIMException e) {
                throw new CharonException("Error in retrieving SCIM Group information from database.", e);
            }
        }
    }

    /**
     * Prepend the domain name in front of the attribute value to be searched.
     *
     * @param attributeValue
     * @param domainName
     * @return
     */
    private String prependDomainNameToTheAttributeValue(String attributeValue, String domainName) {

        if (StringUtils.isNotEmpty(domainName)) {
            return domainName + CarbonConstants.DOMAIN_SEPARATOR + attributeValue;
        } else {
            return attributeValue;
        }
    }

    /**
     * Return group names when search using meta data; list of groups.
     *
     * @param attributeName   Attribute name which is used to search.
     * @param filterOperation Operator value.
     * @param attributeValue  Search value.
     * @param domainName      Domain to be filtered.
     * @return list of groups
     * @throws org.wso2.carbon.user.core.UserStoreException
     * @throws IdentitySCIMException
     */
    private String[] getGroupNamesFromDB(String attributeName, String filterOperation, String attributeValue,
            String domainName) throws org.wso2.carbon.user.core.UserStoreException, IdentitySCIMException {

        String searchAttribute = getSearchAttribute(attributeName, filterOperation, attributeValue,
                SQL_FILTERING_DELIMITER);
        SCIMGroupHandler groupHandler = new SCIMGroupHandler(carbonUM.getTenantId());
        if (log.isDebugEnabled()) {
            log.debug(String.format("Filtering roleNames from DB from search attribute: %s", searchAttribute));
        }
        return groupHandler.getGroupListFromAttributeName(attributeName, searchAttribute, domainName);
    }


    private boolean isPaginatedUserStoreAvailable() {

        String enablePaginatedUserStore = IdentityUtil.getProperty(ENABLE_PAGINATED_USER_STORE);
        if (StringUtils.isNotBlank(enablePaginatedUserStore)) {
            return Boolean.parseBoolean(enablePaginatedUserStore);
        }
        return true;
    }

    /**
     * Check whether claim is an immutable claim.
     *
     * @param claim claim URI.
     * @return
     */
    private boolean isImmutableClaim(String claim) throws UserStoreException {

        Map<String, String> claimMappings = SCIMCommonUtils.getSCIMtoLocalMappings();

        return claim.equals(claimMappings.get(SCIMConstants.CommonSchemaConstants.ID_URI)) ||
                claim.equals(claimMappings.get(SCIMConstants.UserSchemaConstants.USER_NAME_URI)) ||
                claim.equals(claimMappings.get(SCIMConstants.UserSchemaConstants.ROLES_URI + "." + SCIMConstants.DEFAULT)) ||
                claim.equals(claimMappings.get(SCIMConstants.CommonSchemaConstants.CREATED_URI)) ||
                claim.equals(claimMappings.get(SCIMConstants.CommonSchemaConstants.LAST_MODIFIED_URI)) ||
                claim.equals(claimMappings.get(SCIMConstants.CommonSchemaConstants.LOCATION_URI)) ||
                claim.equals(claimMappings.get(SCIMConstants.UserSchemaConstants.FAMILY_NAME_URI)) ||
                claim.contains(UserCoreConstants.ClaimTypeURIs.IDENTITY_CLAIM_URI);
    }

    /**
     * Get the local claims mapped to the required SCIM claims.
     *
     * @param scimToLocalClaimsMap SCIM claims to local claims map.
     * @param requiredAttributes   required attributes of the user.
     * @return list of required claims in local dialect.
     */
    private List<String> getRequiredClaimsInLocalDialect(Map<String, String> scimToLocalClaimsMap,
            Map<String, Boolean> requiredAttributes) {

        List<String> requiredClaims = getOnlyRequiredClaims(scimToLocalClaimsMap.keySet(), requiredAttributes);
        List<String> requiredClaimsInLocalDialect;
        if (MapUtils.isNotEmpty(scimToLocalClaimsMap)) {
            scimToLocalClaimsMap.keySet().retainAll(requiredClaims);
            requiredClaimsInLocalDialect = new ArrayList<>(scimToLocalClaimsMap.values());
        } else {
            if (log.isDebugEnabled()) {
                log.debug("SCIM to Local Claim mappings list is empty.");
            }
            requiredClaimsInLocalDialect = new ArrayList<>();
        }
        return requiredClaimsInLocalDialect;
    }

    /**
     * Evaluate old user claims and the new claims. Then DELETE, ADD and MODIFY user claim values. The DELETE,
     * ADD and MODIFY operations are done in the same order.
     *
     * @param user {@link User} object.
     * @param oldClaimList User claim list for the user's existing state.
     * @param newClaimList User claim list for the user's new state.
     * @throws UserStoreException Error while accessing the user store.
     * @throws CharonException {@link CharonException}.
     */
    private void updateUserClaims(User user, Map<String, String> oldClaimList,
                                  Map<String, String> newClaimList) throws UserStoreException, CharonException {

        Map<String, String> userClaimsToBeAdded = new HashMap<>(newClaimList);
        Map<String, String> userClaimsToBeDeleted = new HashMap<>(oldClaimList);
        Map<String, String> userClaimsToBeModified = new HashMap<>();

        // Get all the old claims, which are not available in the new claims.
        userClaimsToBeDeleted.keySet().removeAll(newClaimList.keySet());

        // Get all the new claims, which are not available in the existing claims.
        userClaimsToBeAdded.keySet().removeAll(oldClaimList.keySet());

        // Get all new claims, which are only modifying the value of an existing claim.
        for (Map.Entry<String, String> eachNewClaim : newClaimList.entrySet()) {
            if (oldClaimList.containsKey(eachNewClaim.getKey()) &&
                    !oldClaimList.get(eachNewClaim.getKey()).equals(eachNewClaim.getValue())) {
                userClaimsToBeModified.put(eachNewClaim.getKey(), eachNewClaim.getValue());
            }
        }

        // Remove user claims.
        for (Map.Entry<String, String> entry : userClaimsToBeDeleted.entrySet()) {
            if (!isImmutableClaim(entry.getKey())) {
                carbonUM.deleteUserClaimValue(user.getUserName(), entry.getKey(), null);
            }
        }

        // Update user claims.
        userClaimsToBeModified.putAll(userClaimsToBeAdded);
        carbonUM.setUserClaimValues(user.getUserName(), userClaimsToBeModified, null);
    }
}
