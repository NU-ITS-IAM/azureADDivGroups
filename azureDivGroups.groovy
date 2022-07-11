#!/usr/bin/env groovy

// Declare imports
import groovy.sql.Sql
import com.unboundid.asn1.*
import com.unboundid.ldap.sdk.*
import com.unboundid.ldap.sdk.controls.*
import com.unboundid.util.*


// Declare which environment to use - note this is taken from the command line argument.
String TargetEnv
//TargetEnv = 'PROD'  //The TargetEnv can be hard set for testing in the script by uncommenting this line.

if (args || TargetEnv) {
    if (args && !TargetEnv) {TargetEnv = args[0].toUpperCase()} //This will use the command line argument if it exists to set the target.
    if (!(TargetEnv == 'PROD' || TargetEnv == 'TEST' || TargetEnv == 'DEV')) {
        println "\nERROR: An invalid environment value [$TargetEnv] was specified.  Please re-run the script with a valid environment value as an argument"
        println "       Valid enviornments are PROD, TEST, DEV, and DEV3\n\n"
        System.exit(1)
    }    
} else {
    println "\nERROR: No environment value was specified.  Please re-run the script with a valid environment value as an argument"
    println "       Valid enviornments are PROD, TEST, DEV, and DEV3\n\n"
    System.exit(1)
}

println "\n${(new Date()).format("yyyy-MM-dd HH:mm:ss")}   Beginning Employee Azure SSO Division Group Maintenance Script [$TargetEnv]\n"

// Select correct properties file
File propertiesFile
if (TargetEnv == 'PROD')         {propertiesFile = new File('properties/prod.properties')}
else if (TargetEnv == 'TEST')    {propertiesFile = new File('properties/test.properties')}
else if (TargetEnv == 'DEV')     {propertiesFile = new File('properties/dev.properties')}

// Load properties
Properties AccessProps = new Properties()
propertiesFile.withInputStream {AccessProps.load(it)}

// Define LDAP Options
LDAPConnectionOptions options = new LDAPConnectionOptions();
options.setAutoReconnect(true);
ASN1OctetString resumeCookie = null;

// Database Connections
Sql.LOG.level = java.util.logging.Level.SEVERE
def WavesetDB
try{WavesetDB = Sql.newInstance(AccessProps.waveset_connection_string, AccessProps.waveset_username, AccessProps.waveset_password, AccessProps.waveset_class)}
catch(Exception SQLError) {throw SQLError}

// LDAP Connections
LDAPConnection ADConn = new LDAPConnection(options, AccessProps.ad_hostname, AccessProps.ad_port as int, AccessProps.ad_bindname, AccessProps.ad_pass)

// Declare AD Values
String ADBaseDN     = AccessProps.ad_basedn
String ADStuBaseDN  = "ou=people,${ADBaseDN}"
String ADFacStaffDN = "CN=Users,${ADBaseDN}"
String DivisionOUDN = 'OU=Division,OU=Faculty and Staff,OU=Dynamic Groups,OU=NUNET GROUPS,DC=nunet,DC=neu,DC=edu'

// Declare SQL Queries
String Qry_Division_List = """
    SELECT DIVISION FROM NEU_BNR_JOB WHERE DIVISION IS NOT NULL GROUP BY DIVISION ORDER BY DIVISION
"""

Qry_EmployeesByDivision = '''
    select distinct nuid
    from neu_bnr_job
    where 
        division = :DivisionCode
        and effective_date <= sysdate
        and status = 'A'
    union
    select distinct nuid
    from neu_sponsor
    where 
        division = :DivisionCode
        and effective_from_date <= to_char(sysdate, 'YYYYMMDD')
        and effective_to_date >= to_char(sysdate, 'YYYYMMDD')

'''

// Get list of all active AD account usernames
//--------------------------
def NUID_ADUsers = []
Filter ADAllUsers = Filter.create("(&(sAMAccountType=805306368)(neuNUID=*))") //Search all users
println "${(new Date()).format("yyyy-MM-dd HH:mm:ss")}   Searching AD: Employee OU..."
SearchRequest ADStudentSearchRequest = new SearchRequest(ADFacStaffDN,SearchScope.SUB,ADAllUsers,"neuNUID","sn");
resumeCookie = null;
while (true) {
    ADStudentSearchRequest.setControls(new SimplePagedResultsControl(2000, resumeCookie));
    SearchResult ADFacStaffSearchResult = ADConn.search(ADStudentSearchRequest);
    for (SearchResultEntry ADSearchEntry : ADFacStaffSearchResult.getSearchEntries()) {
        def UserNUID   = ADSearchEntry.getAttributeValue("neuNUID")
        def UserDN     = ADSearchEntry.getDN();

        NUID_ADUsers.add(UserNUID)
    }
    LDAPTestUtils.assertHasControl(ADFacStaffSearchResult,SimplePagedResultsControl.PAGED_RESULTS_OID);
    SimplePagedResultsControl responseControl = SimplePagedResultsControl.get(ADFacStaffSearchResult);
    if (responseControl.moreResultsToReturn()) {
        resumeCookie = responseControl.getCookie();
    }
    else {break;}
}

// Get list of all IAM DIV Groups
//--------------------------
def AD_Div_Groups = []
Filter SearchDivGroups = Filter.create("(sAMAccountName=IAM_Division_*)")
println "${(new Date()).format("yyyy-MM-dd HH:mm:ss")}   Searching AD: Division Groups..."
SearchRequest ADGroupSearch = new SearchRequest(DivisionOUDN,SearchScope.SUB,SearchDivGroups);
resumeCookie = null;
while (true) {
    ADGroupSearch.setControls(new SimplePagedResultsControl(2000, resumeCookie));
    SearchResult ADGroupSearchResult = ADConn.search(ADGroupSearch);
    for (SearchResultEntry ADSearchEntry : ADGroupSearchResult.getSearchEntries()) {
        def GroupName   = ADSearchEntry.getAttributeValue("samAccountName")
        def UserDN     = ADSearchEntry.getDN();

        AD_Div_Groups.add(GroupName)
    }
    LDAPTestUtils.assertHasControl(ADGroupSearchResult,SimplePagedResultsControl.PAGED_RESULTS_OID);
    SimplePagedResultsControl responseControl = SimplePagedResultsControl.get(ADGroupSearchResult);
    if (responseControl.moreResultsToReturn()) {
        resumeCookie = responseControl.getCookie();
    }
    else {break;}
}

// Get list of all divisions currently in Waveset
def DivisionList = []
WavesetDB.eachRow(Qry_Division_List){DivisionList.add(it.division)}

// Process membership for each AD group.  Script will compare list of Wavevset Divisions against available AD divisions and will set an ErrorCount if there is an AD group missing.  
// The AD group needs to be created manually by someone with the necessary AD privileges to create groups.

def EligibleUserCount    = []
def MembershipTotalCount = []
def MissingDivGroups     = []
ErrorCount=0

println "\n\n"
for (Division in DivisionList) {
    String EligibleUsers; String MemberCount

    String GroupName = "IAM_Division_${Division}"

    // Check the division to see if there is a current AD group created for it.  
    if (!(AD_Div_Groups.contains(GroupName))) {
        println "ERROR: Missing AD group for $Division"
        MissingDivGroups.add(GroupName)
        ErrorCount++
        continue
    }

    println "\n${(new Date()).format("yyyy-MM-dd HH:mm:ss")}   Processing $GroupName for Division $Division"
    
    String DN_Group  = "CN=${GroupName},${DivisionOUDN}"
    def NUID_EligibleUsers = []
    def NUID_GroupMembers  = []

    // Get list of eligible users from Auth Source
    WavesetDB.eachRow(Qry_EmployeesByDivision,[DivisionCode:Division]){NUID_EligibleUsers.add(it.nuid)}
    
    // Remove users not in AD, remove duplicates, and sort
    NUID_EligibleUsers = NUID_ADUsers.intersect(NUID_EligibleUsers).unique().sort()
    
    // Print Eligible 
    EligibleUsers = "   Division: $Division - Users: ${NUID_EligibleUsers.size()}"
    EligibleUserCount.add(EligibleUsers)
    println EligibleUsers

    // Get current members
    NUID_GroupMembers = GetMemberOf('NUID',DN_Group,ADFacStaffDN,ADConn)
    MemberCount = "   IAM_Division_${Division} : ${NUID_GroupMembers.size()}"
    println MemberCount

    // Process ADD and REMOVE lists
    def AddMembers    = NUID_EligibleUsers - NUID_GroupMembers
    def RemoveMembers = NUID_GroupMembers - NUID_EligibleUsers

    // Process group Additions
    UpdateGroupMembershipByNUID ('Add'   ,DN_Group,ADFacStaffDN,ADConn,AddMembers)

    // Process group Removals
    UpdateGroupMembershipByNUID ('Remove',DN_Group,ADFacStaffDN,ADConn,RemoveMembers)

    // Get current members
    NUID_GroupMembers = GetMemberOf('NUID',DN_Group,ADFacStaffDN,ADConn)
    MemberCount = "   IAM_Division_${Division} : ${NUID_GroupMembers.size()}"
    MembershipTotalCount.add(MemberCount)
    println "$MemberCount\n"    
}

println "\n\n\n${(new Date()).format("yyyy-MM-dd HH:mm:ss")}   Get list eligible Users for each group from IAMPROD Auth Source"
EligibleUserCount.each{println it}

println "\n${(new Date()).format("yyyy-MM-dd HH:mm:ss")}   Membership totals for each group"
MembershipTotalCount.each{println it}

if (MissingDivGroups) {
    println "\n${(new Date()).format("yyyy-MM-dd HH:mm:ss")}   Missing AD Groups for the following Divisions"
    MissingDivGroups.each{println it}
}

//======================================================================
// Functions
//======================================================================

// Get Group Membership
def GetMemberOf (ReturnType,GroupDN,SearchBase,LDAPObject) {
    if (!ReturnType) {println "Return Type not specificed.  Please specify DN or NUID"; System.exit(2)}
    ReturnType = ReturnType.toUpperCase()

    GroupMemberDN   = []
    GroupMemberNUID = []

    int numSearches = 0;
    int totalEntriesReturned = 0;

    SearchRequest searchRequest = new SearchRequest(SearchBase,SearchScope.SUB, Filter.createEqualityFilter("memberof", GroupDN ));
    ASN1OctetString resumeCookie = null;
    while (true) {
        searchRequest.setControls(new SimplePagedResultsControl(2000, resumeCookie));
        SearchResult searchResult = LDAPObject.search(searchRequest);
        numSearches++;
        totalEntriesReturned += searchResult.getEntryCount();
        for (SearchResultEntry e : searchResult.getSearchEntries()) {
            GroupMemberDN.add( e.getAttributeValue("distinguishedname") )
            GroupMemberNUID.add( e.getAttributeValue("neuNUID") )
        }

        LDAPTestUtils.assertHasControl(searchResult,SimplePagedResultsControl.PAGED_RESULTS_OID);
        SimplePagedResultsControl responseControl = SimplePagedResultsControl.get(searchResult);
        if (responseControl.moreResultsToReturn()) {
            resumeCookie = responseControl.getCookie();
        }
        else {break}
    }

    if (ReturnType == 'DN') {
        return GroupMemberDN
    } else if (ReturnType == 'NUID') {
        return GroupMemberNUID
    } else {println "Return Type invalid [$ReturnType].  Please specify DN or NUID"; System.exit(2)}
    
}

def UpdateGroupMembershipByNUID (OperationType,GroupDN,SearchBase,LDAPObject,ProcessList) {
    // Need to add back in the no license override
    def UserDNList = []
    OperationType   = OperationType.toUpperCase()

    println "\nProcessing $OperationType $GroupDN\n"

    if (ProcessList.size() > 0){
        for (Item in ProcessList.sort()) {
            Filter SearchFilter = Filter.createEqualityFilter("neuNUID", Item)

            SearchRequest searchRequest = new SearchRequest(SearchBase,SearchScope.SUB,SearchFilter);
            SearchResult searchResult = LDAPObject.search(searchRequest);
            SearchEntries = searchResult.getSearchEntries();

            if (SearchEntries.size() == 1) {
                SearchResultEntry entry = SearchEntries.get(0);
                UserDN = entry.getDN();    
                UserDNList.push(UserDN)
            }else if (SearchEntries.size() == 0) {
                println "   Did not find user ${Item}"
            }else {
                println "   Multiple results for ${Item}: ${SearchEntries.size()}"
                SearchEntries.each{
                    UserDNList.push(it.getDN())
                }
            }
        }
    }

    if (OperationType == 'ADD') {
        for (UserDN in UserDNList){
            println "   ADD     " + UserDN
            Modification mod = new Modification(ModificationType.ADD, "member", UserDN); 
            ModifyRequest modifyRequest = new ModifyRequest( GroupDN, mod);
            try {
                LDAPResult modifyResult = LDAPObject.modify(modifyRequest);
            } catch(Exception LDAPError) {
                if (LDAPError.toString().contains('resultCode=68')) {
                    println "entryAlreadyExists: User [${UserDN}] is already a member of [${GroupDN}]"
                } else {throw LDAPError}
            }
        }
    }

    if (OperationType == 'REMOVE') {
        for (UserDN in UserDNList){
            println "   REMOVE  " + UserDN
            Modification mod = new Modification(ModificationType.DELETE, "member", UserDN); 
            ModifyRequest modifyRequest = new ModifyRequest( GroupDN, mod);
            try {
                LDAPResult modifyResult = LDAPObject.modify(modifyRequest);
            } catch(Exception LDAPError) {
                if (LDAPError.toString().contains('resultCode=53')) {
                    println "   unwillingToPerform: Cannot remove. User [${UserDN}] is not a member of [${GroupDN}]"
                } else {throw LDAPError}
            }
        }
    }
}

if (ErrorCount > 0) {
    println "\n\nScript Errors: $ErrorCount"
    println "\n\n\n${(new Date()).format("yyyy-MM-dd HH:mm:ss")}   *** End of Script *** \n\n\n"
    System.exit(2)
} else {
    println "\n\n\n${(new Date()).format("yyyy-MM-dd HH:mm:ss")}   *** End of Script *** \n\n\n"
}