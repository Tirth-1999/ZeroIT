package org.fairshare.app.ui

import androidx.compose.ui.backhandler.BackHandler
import org.fairshare.app.platform.*
import org.fairshare.app.math.BigInteger
import org.fairshare.app.math.BigDecimal
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.fairshare.app.data.Repository
import org.fairshare.app.domain.Ledger
import org.fairshare.app.model.*
private val Teal = Color(0xFF17675A)
private val Coral = Color(0xFFB84939)
private val LightColors = lightColorScheme(primary = Teal, onPrimary = Color.White, primaryContainer = Color(0xFFD6EEE4), onPrimaryContainer = Color(0xFF103E35), secondary = Color(0xFF6D6558), background = Color(0xFFF7F6F0), surface = Color(0xFFF7F6F0), surfaceContainer = Color(0xFFEEEDE6), surfaceContainerLow = Color(0xFFFFFFFF), error = Coral)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF92D6BD), onPrimary = Color(0xFF092019),
    primaryContainer = Color(0xFF292929), onPrimaryContainer = Color(0xFFF0F0F0),
    secondary = Color(0xFFC4C4C4), onSecondary = Color(0xFF202020),
    secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color(0xFFF0F0F0),
    background = Color(0xFF0B0B0B), onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF101010), onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF292929), onSurfaceVariant = Color(0xFFBDBDBD),
    surfaceContainerLowest = Color(0xFF080808), surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF202020), surfaceContainerHigh = Color(0xFF282828),
    surfaceContainerHighest = Color(0xFF323232),
    surfaceBright = Color(0xFF383838), surfaceDim = Color(0xFF101010),
    outline = Color(0xFF808080), outlineVariant = Color(0xFF404040),
    inverseSurface = Color(0xFFE5E5E5), inverseOnSurface = Color(0xFF171717),
    inversePrimary = Teal, error = Color(0xFFFFB4A8),
)


@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun FairshareApp(repository: Repository, onBackgroundChanged: (Color, String) -> Unit = { _, _ -> }) {
    val state by repository.state.collectAsState()
    val dark = when (state.themeMode) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }
    val reducedMotion = rememberReducedMotion()
    val layoutDirection = LocalLayoutDirection.current
    var selectedRootTab by rememberSaveable { mutableStateOf("groups") }
    val homePresentation = remember(state) { homeLedgerPresentation(state) }
    val primaryTabs = listOf("groups", "friends", "activity", "account")
    var navigationVisible by remember { mutableStateOf(false) }
    var navigationEnabled by remember { mutableStateOf(true) }
    var navigationColors by remember { mutableStateOf(if (dark) DarkColors else LightColors) }
    var navigationHeight by remember { mutableIntStateOf(0) }
    val shellDensity = LocalDensity.current
    val shellFocus = LocalFocusManager.current
    val shellKeyboard = LocalSoftwareKeyboardController.current
    var requestedRoot by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedFriend by rememberSaveable { mutableStateOf<String?>(null) }
    val tabSessions = rememberSaveableStateHolder()
    // Keep every tab's navigation arguments and child saveable state together.
    // Switching tabs must not recreate its previous screen, scroll or draft.
    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = selectedRootTab,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val tabDirection = if (primaryTabs.indexOf(targetState) > primaryTabs.indexOf(initialState)) 1 else -1
            val direction = tabDirection * if (layoutDirection == LayoutDirection.Rtl) -1 else 1
            if (reducedMotion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            else slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { direction * it } togetherWith
                slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -direction * it }
        }, label = "tab content slide",
    ) { rootTab ->
    tabSessions.SaveableStateProvider(rootTab) {
    var route by rememberSaveable { mutableStateOf(rootTab) }
    var groupId by rememberSaveable { mutableStateOf("") }
    val themedGroup = state.groups.find { it.id == groupId && !it.isNonGroup }
    val groupRoutes = setOf("group", "group_settings", "group_defaults", "members", "member", "expense", "detail", "settlement")
    val baseColors = if (dark) DarkColors else LightColors
    val colors = if (route in groupRoutes && themedGroup != null) groupColors(baseColors, themedGroup.theme, dark) else baseColors
    if (rootTab == selectedRootTab) PlatformAppearance(dark)
    CompositionLocalProvider(LocalGroupAppearance provides state.themeMode, LocalGroupBaseColors provides baseColors) {
    MaterialTheme(colorScheme = colors, shapes = Shapes(extraSmall = RoundedCornerShape(14.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp))) {
        var friendId by rememberSaveable { mutableStateOf("") }
        var detailReturn by rememberSaveable { mutableStateOf("group") }
        var groupParent by rememberSaveable { mutableStateOf("groups") }
        var searchOpen by rememberSaveable { mutableStateOf(false) }
        var homeQuery by rememberSaveable { mutableStateOf("") }
        var balanceToolsOpen by remember { mutableStateOf(false) }
        var groupsFilterName by rememberSaveable { mutableStateOf("ALL") }
        var friendsFilterName by rememberSaveable { mutableStateOf("ALL") }
        var groupsSortName by rememberSaveable { mutableStateOf("NEWEST") }
        var friendsSortName by rememberSaveable { mutableStateOf("NAME") }
        var groupsSortCurrency by rememberSaveable { mutableStateOf("") }
        var friendsSortCurrency by rememberSaveable { mutableStateOf("") }
        val homeFilter = BalanceFilter.valueOf(if (route == "friends") friendsFilterName else groupsFilterName)
        val homeSort = BalanceSort.valueOf(if (route == "friends") friendsSortName else groupsSortName)
        val homeCurrencies = if (route == "friends") homePresentation.friendCurrencies else homePresentation.groupCurrencies
        val savedSortCurrency = if (route == "friends") friendsSortCurrency else groupsSortCurrency
        val homeSortCurrency = savedSortCurrency.takeIf { it in homeCurrencies } ?: homeCurrencies.firstOrNull().orEmpty()
        var addFriend by rememberSaveable { mutableStateOf(false) }
        var newFriendName by rememberSaveable { mutableStateOf("") }
        var expenseChooser by rememberSaveable { mutableStateOf(false) }
        var homeAddExpanded by remember { mutableStateOf(false) }
        LaunchedEffect(route) { homeAddExpanded = false }
        var draftFriendId by rememberSaveable { mutableStateOf("") }
        var nonGroupPayment by rememberSaveable { mutableStateOf(false) }
        var expenseStep by rememberSaveable { mutableIntStateOf(0) }
        var membersReturn by rememberSaveable { mutableStateOf("group") }
        var expenseId by rememberSaveable { mutableStateOf("") }
        var groupTab by rememberSaveable { mutableIntStateOf(0) }
        // A successful write starts a fresh viewport, rather than retaining the
        // previous first-visible item by stable key. Browsing/cancel keep position.
        var successfulWriteGeneration by rememberSaveable { mutableIntStateOf(0) }
        val expenseListState = rememberSaveable(groupId, successfulWriteGeneration, saver = LazyListState.Saver) { LazyListState() }
        val activityListState = rememberSaveable(groupId, successfulWriteGeneration, saver = LazyListState.Saver) { LazyListState() }
        val balanceScroll = rememberSaveable(groupId, successfulWriteGeneration, saver = ScrollState.Saver) { ScrollState(0) }
        val totalsScroll = rememberSaveable(groupId, successfulWriteGeneration, saver = ScrollState.Saver) { ScrollState(0) }
        val settlementScroll = rememberSaveable(groupId, successfulWriteGeneration, saver = ScrollState.Saver) { ScrollState(0) }
        var settlementEditId by rememberSaveable { mutableStateOf("") }
        var settlementFrom by rememberSaveable { mutableStateOf("") }
        var settlementTo by rememberSaveable { mutableStateOf("") }
        var settlementAmount by rememberSaveable { mutableStateOf("") }
        var settlementCurrency by rememberSaveable { mutableStateOf("") }
        var welcomeComplete by rememberSaveable { mutableStateOf(false) }
        var selectedMemberIds by rememberSaveable { mutableStateOf(listOf<String>()) }
        var newMemberNames by rememberSaveable { mutableStateOf(listOf<String>()) }
        var memberQuery by rememberSaveable { mutableStateOf("") }
        var memberReview by rememberSaveable { mutableStateOf(false) }
        var memberReturn by rememberSaveable { mutableStateOf("group") }
        var confirmDiscardMembers by rememberSaveable { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var expenseDirty by rememberSaveable { mutableStateOf(false) }
        var confirmDiscard by rememberSaveable { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var savedNotice by remember { mutableStateOf<Pair<String, String>?>(null) }
        var celebration by remember { mutableStateOf<SaveCelebrationEvent?>(null) }
        var celebrationSequence by remember { mutableStateOf(0L) }
        fun celebrate(message: String) { celebrationSequence++; celebration = SaveCelebrationEvent(celebrationSequence, message) }
        // A successful write may change route in the same frame. Its acknowledgement
        // owns its lifetime so navigation cannot erase it before the first frame.
        LaunchedEffect(route) { if (savedNotice?.first != route) savedNotice = null }
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        fun task(confirmation: String? = null, animationMessage: String? = null, resetScroll: Boolean = false, action: suspend () -> Unit) {
            if (busy) return
            savedNotice = null
            busy = true
            scope.launch {
                try {
                    action()
                    if (resetScroll) successfulWriteGeneration++
                    focus.clearFocus(force = true)
                    keyboard?.hide()
                    if (confirmation != null && confirmation != "Name saved") savedNotice = route to confirmation
                    if (animationMessage != null) celebrate(animationMessage)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (e: Exception) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(e.message ?: "Could not save. Please try again.") } } finally { busy = false }
            }
        }
        if (balanceToolsOpen && route in listOf("groups", "friends")) HomeBalanceToolsDialog(
            homeFilter, homeSort, homeSortCurrency, homeCurrencies,
            onFilter = { if (route == "friends") friendsFilterName = it.name else groupsFilterName = it.name },
            onSort = { if (route == "friends") friendsSortName = it.name else groupsSortName = it.name },
            onCurrency = { if (route == "friends") friendsSortCurrency = it else groupsSortCurrency = it },
            onDismiss = { balanceToolsOpen = false },
            allowNewest = route == "groups"
        )
        val roots = listOf("groups", "friends", "activity", "account")
        fun openRoot(tab: String) {
            homeAddExpanded = false; balanceToolsOpen = false
            // Explicit completion/redirect actions can request a root destination.
            // Bottom-tab taps below only select the saved session.
            requestedRoot = tab; selectedRootTab = tab
        }
        LaunchedEffect(requestedRoot) {
            if (requestedRoot == rootTab) {
                route = rootTab; searchOpen = false; homeQuery = ""
                requestedRoot = null
            }
        }
        LaunchedEffect(requestedFriend) {
            if (rootTab == "friends" && requestedFriend != null) {
                friendId = requestedFriend!!; detailReturn = "friend"; route = "friend"
                requestedFriend = null
            }
        }
        LaunchedEffect(state.groups, state.expenses, state.settlements, state.members) {
            route = validTabSessionRoute(state, route, rootTab, groupId, expenseId, settlementEditId, friendId)
        }
        fun openGroup(id: String, parent: String = rootTab) { groupId = if (state.groups.find { it.id == id }?.isNonGroup == true) NON_GROUP_ID else id; groupParent = parent; groupTab = 0; detailReturn = if (groupId == NON_GROUP_ID) "nongroup" else "group"; route = if (state.groups.find { it.id == id }?.isHidden == true) "group_settings" else detailReturn }
        fun newNonGroupExpense(friend: String = "", parent: String = "nongroup") { if (parent == "nongroup") groupParent = rootTab; draftFriendId = friend; groupId = NON_GROUP_ID; expenseId = ""; expenseStep = 0; detailReturn = parent; expenseDirty = false; route = "expense" }
        fun openFriend(id: String) { friendId = id; detailReturn = "friend"; route = "friend" }
        fun openExpense(id: String, returnTo: String) { expenseId = id; groupId = state.expenses.first { it.id == id }.groupId; detailReturn = returnTo; route = "detail" }
        fun editActivityExpense(id: String, returnTo: String) { openExpense(id, returnTo); expenseStep = 0; expenseDirty = false; route = "expense" }
        fun editActivityPayment(id: String, returnTo: String) {
            val payment = state.settlements.first { it.id == id }
            settlementEditId = id; groupId = payment.groupId; detailReturn = returnTo
            settlementFrom = payment.fromId; settlementTo = payment.toId
            settlementCurrency = Ledger.settlementCurrency(state, payment)
            settlementAmount = plainMoney(payment.amountMinor, settlementCurrency); route = "settlement"
        }
        fun leaveScreen() { route = when (route) { "group", "nongroup" -> groupParent; "expense" -> if (expenseId.isNotBlank()) "detail" else detailReturn; "group_defaults" -> "group_settings"; "group_settings" -> if (state.groups.find { it.id == groupId }?.isHidden == true) groupParent else "group"; "member" -> memberReturn; "members" -> membersReturn; "settlement", "detail" -> detailReturn; else -> rootTab } }
        fun back() { if (homeAddExpanded) { homeAddExpanded = false; return }; if (!busy) {
            if (route == "expense" && expenseStep > 0) expenseStep--
            else if (route in listOf("group", "nongroup") && groupTab > 0) groupTab = 0
            else if (route == "member" && memberReview) memberReview = false
            else if (route == "member" && selectedMemberIds.size + newMemberNames.size > 0) confirmDiscardMembers = true
            else if (route == "expense" && expenseDirty) confirmDiscard = true
            else leaveScreen()
        } }
        fun openMembers() { memberReturn = "members"; selectedMemberIds = emptyList(); newMemberNames = emptyList(); memberQuery = ""; memberReview = false; route = "member" }
        BackHandler(enabled = rootTab == selectedRootTab && (route !in roots || homeAddExpanded) && state.onboardingComplete) { back() }
        BackHandler(enabled = rootTab == selectedRootTab && !state.onboardingComplete && welcomeComplete) { if (!busy) welcomeComplete = false }
        val nonGroupState = homePresentation.nonGroupState
        val nonGroup = homePresentation.nonGroup
        val draftMembers = if (draftFriendId.isNotEmpty()) listOf(SELF_ID, draftFriendId) else state.members.map { it.id }
        val group = if (groupId == NON_GROUP_ID) nonGroup.copy(memberIds = draftMembers) else state.groups.find { it.id == groupId }
        val membersGroup = if (membersReturn == "nongroup") nonGroup else group
        val showBottom = state.onboardingComplete && (route in roots || route in listOf("group", "nongroup", "friend", "detail", "settings"))
        val groupPageColor = if (route == "group" && group != null && group.theme != GroupTheme.CLASSIC)
            colors.primary.copy(alpha = .05f).compositeOver(colors.surface) else colors.surface
        SideEffect {
            if (rootTab == selectedRootTab) {
                onBackgroundChanged(groupPageColor, state.themeMode)
                navigationVisible = showBottom
                navigationEnabled = !busy
                navigationColors = colors
            }
        }
        Box(Modifier.fillMaxSize()) {
        Scaffold(containerColor = if (route == "group") groupPageColor else colors.background,
            snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            Column {
            if (state.onboardingComplete) TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = groupPageColor, scrolledContainerColor = groupPageColor), title = {
                Text(when (route) { "groups" -> "Groups"; "nongroup" -> "Non-group expenses"; "friends" -> "Friends"; "activity" -> "Activity"; "account", "settings" -> "Account"; "friend" -> memberName(state, friendId); "create" -> "New group"; "group_settings" -> "Group settings"; "group_defaults" -> "My default split"; "expense" -> if (expenseId.isEmpty()) "Add expense" else "Edit expense"; "detail" -> "Expense details"; "members" -> "Group members"; "member" -> if (memberReview) "Review members" else "Add group members"; "settlement" -> if (settlementEditId.isBlank()) "Record settlement" else "Edit payment"; else -> group?.name?.let(::compactGroupTitle) ?: "Group" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(if (route in listOf("group", "nongroup")) "group_title" else "screen_title").semantics { if (route in listOf("group", "nongroup")) contentDescription = if (route == "nongroup") "Non-group expenses" else group?.name.orEmpty() })
            }, navigationIcon = { if (route !in roots) IconButton(onClick = { back() }, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, actions = {
                if (route in listOf("groups", "friends")) {
                    IconButton(onClick = { homeAddExpanded = false; searchOpen = !searchOpen; if (!searchOpen) homeQuery = "" }) { Icon(Icons.Default.Search, "Search ${route}") }
                    IconButton(onClick = { homeAddExpanded = false; balanceToolsOpen = true }, modifier = Modifier.testTag("home_balance_tools")) {
                        BadgedBox(badge = { if (homeFilter != BalanceFilter.ALL || homeSort != BalanceSort.NAME) Badge() }) { Icon(Icons.Default.Sort, "Sort and filter ${route}") }
                    }
                    if (route == "groups") IconButton(onClick = { homeAddExpanded = false; route = "create" }, modifier = Modifier.testTag("create_group")) { Icon(Icons.Default.GroupAdd, "Create a group") }
                    else IconButton(onClick = { newFriendName = ""; addFriend = true }, modifier = Modifier.testTag("add_friend")) { Icon(Icons.Default.PersonAdd, "Add a friend") }
                }
                if (route in listOf("group", "nongroup")) {
                    val membersCount = (if (route == "nongroup") nonGroup else group)?.activeMemberIds?.size ?: 0
                    FilledTonalButton(
                        onClick = { membersReturn = route; route = "members" }, enabled = !busy,
                        shape = RoundedCornerShape(24.dp), contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 48.dp).testTag("group_members")
                            .semantics { contentDescription = "View $membersCount group members" },
                    ) {
                        Icon(Icons.Default.Groups, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(membersCount.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (route == "group" && group != null && !group.isNonGroup) IconButton(onClick = { route = "group_settings" }, enabled = !busy, modifier = Modifier.testTag("group_settings")) { Icon(Icons.Default.Settings, "Group settings") }
                if (route == "member" && !memberReview) TextButton(onClick = { memberReview = true }, enabled = !busy && selectedMemberIds.size + newMemberNames.size > 0, modifier = Modifier.testTag("member_next")) { Text("Next (${selectedMemberIds.size + newMemberNames.size})") }
            })
            savedNotice?.takeIf { it.first == route }?.let { (_, message) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp).testTag("save_confirmation").semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            }
        }, bottomBar = {
            if (showBottom) Spacer(Modifier.fillMaxWidth().height(
                if (navigationHeight > 0) with(shellDensity) { navigationHeight.toDp() }
                else 70.dp + floatingNavigationBottomGap(with(shellDensity) { WindowInsets.navigationBars.getBottom(this).toDp() })
            ))
        }, floatingActionButton = {
            if (state.onboardingComplete && route in listOf("group", "nongroup") && groupTab == 0 && group?.isHidden != true) ExtendedFloatingActionButton(onClick = { if (!busy) { if (route == "nongroup") newNonGroupExpense() else { expenseId = ""; expenseStep = 0; detailReturn = "group"; expenseDirty = false; route = "expense" } } }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add expense") }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.testTag("add_expense"))
            else if (state.onboardingComplete && route == "groups") GroupsAddMenu(
                expanded = homeAddExpanded,
                reducedMotion = reducedMotion,
                enabled = !busy,
                onToggle = { homeAddExpanded = !homeAddExpanded },
                onAddGroup = { homeAddExpanded = false; route = "create" },
                onAddExpense = { homeAddExpanded = false; expenseChooser = true }
            )
            else if (state.onboardingComplete && route in listOf("friends", "activity")) ExtendedFloatingActionButton(onClick = { expenseChooser = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add expense") }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.testTag("global_add_expense"))
        }) { padding ->
            EdgeBackGestureHost(
                enabled = rootTab == selectedRootTab && state.onboardingComplete && route !in roots && !busy && !homeAddExpanded && celebration == null && !expenseChooser && !addFriend && !confirmDiscard && !confirmDiscardMembers && !nonGroupPayment,
                onBack = { back() }, modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                AnimatedContent(
                    targetState = if (state.onboardingComplete) route else "onboarding",
                    modifier = Modifier.fillMaxSize().then(if (homeAddExpanded) Modifier.semantics { invisibleToUser() } else Modifier),
                    transitionSpec = {
                        if (reducedMotion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        else (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 24 }) togetherWith fadeOut(tween(100))
                    },
                    label = "screen transition"
                ) { visibleRoute ->
                key(successfulWriteGeneration) {
                if (visibleRoute == "onboarding") {
                    if (!welcomeComplete) WelcomeScreen { welcomeComplete = true }
                    else ProfileScreen(busy, state.profileName, onBack = { welcomeComplete = false }, onContinue = { name -> task { repository.setProfile(name) } })
                }
                else when (visibleRoute) {
                    "groups" -> GroupRefreshContainer(onRefresh = { repository.refresh() }, tagPrefix = "home", successMessage = "Saved expenses and balances reloaded") { GroupsScreen(state, homePresentation, busy, searchOpen, homeQuery, { homeQuery = it }, homeFilter, homeSort, homeSortCurrency, onCreate = { route = "create" }, onGroup = { openGroup(it) }, onDemo = { task(resetScroll = true) { repository.loadDemo() } }, onNonGroup = { groupId = NON_GROUP_ID; groupParent = rootTab; groupTab = 0; route = "nongroup" }) }
                    "friends" -> FriendsScreen(state, homePresentation, searchOpen, homeQuery, { homeQuery = it }, homeFilter, homeSort, homeSortCurrency, { openFriend(it) }, { newFriendName = ""; addFriend = true })
                    "activity" -> GlobalActivityScreen(state, busy, onContext = { id -> openGroup(id) }, onEditExpense = { editActivityExpense(it, "activity") }, onDeleteExpense = { id -> task("Expense deleted", "Expense deleted", resetScroll = true) { repository.deleteExpense(id) } }, onEditSettlement = { editActivityPayment(it, "activity") }, onDeleteSettlement = { id -> task("Payment record deleted", "Payment deleted", resetScroll = true) { repository.deleteSettlement(id) } }, onRestoreExpense = { id -> task("Expense restored", "Expense restored", resetScroll = true) { repository.restoreExpense(id) } })
                    "friend" -> FriendScreen(state, friendId, busy, onAdd = { newNonGroupExpense(friendId, "friend") }, onExpense = { openExpense(it, "friend") }, onGroup = { openGroup(it, "friend") }, onSettle = { id, currency -> settlementEditId = ""; groupId = id; detailReturn = "friend"; settlementCurrency = currency; val context = state.groups.first { it.id == id }; val amount = Ledger.friendBalances(state.copy(groups = listOf(context)), friendId)[currency] ?: 0L; settlementFrom = if (amount > 0) friendId else SELF_ID; settlementTo = if (amount > 0) SELF_ID else friendId; settlementAmount = plainMoneyAbs(amount, currency); route = "settlement" })
                    "create" -> CreateGroupScreen(state.profileName, busy) { name, currency -> task(resetScroll = true) { groupId = repository.createGroup(name, currency, emptyList()); groupParent = rootTab; groupTab = 0; route = "group" } }
                    "account", "settings" -> SettingsScreen(state, busy, onName = { task("Name saved") { repository.setProfile(it) } }, onTheme = { task("Appearance updated") { repository.setTheme(it) } }, onDemo = { task(resetScroll = true) { repository.loadDemoScenarios(); openRoot("groups") } }, onRestore = { id -> task { repository.setGroupVisibility(id, GroupVisibility.ACTIVE) } })
                    "group" -> if (group != null) GroupWorkspaceScreen(state, group, busy, groupTab, { groupTab = it }, listOf(balanceScroll, totalsScroll, settlementScroll), expenseListState, activityListState, onRefresh = { repository.refresh() }, onExpense = { openExpense(it, "group") }, onSettle = { transfer, currency ->
                        settlementEditId = ""; settlementCurrency = currency; detailReturn = "group"; settlementFrom = transfer?.fromId ?: ""; settlementTo = transfer?.toId ?: ""; settlementAmount = transfer?.let { plainMoney(it.amountMinor, currency) } ?: ""; route = "settlement"
                    }, onDeleteSettlement = { task("Payment record deleted", "Payment deleted", resetScroll = true) { repository.deleteSettlement(it) } }, onEditExpense = { editActivityExpense(it, "group") }, onDeleteExpense = { id -> task("Expense deleted", "Expense deleted", resetScroll = true) { repository.deleteExpense(id) } }, onEditSettlement = { editActivityPayment(it, "group") }, onRestoreExpense = { id -> task("Expense restored", "Expense restored", resetScroll = true) { repository.restoreExpense(id) } })
                    "nongroup" -> GroupWorkspaceScreen(nonGroupState, nonGroup, busy, groupTab, { groupTab = it }, listOf(balanceScroll, totalsScroll, settlementScroll), expenseListState, activityListState, onRefresh = { repository.refresh() }, onExpense = { openExpense(it, "nongroup") }, onSettle = { _, currency -> settlementEditId = ""; settlementCurrency = currency; nonGroupPayment = true }, onDeleteSettlement = { task("Payment record deleted", "Payment deleted", resetScroll = true) { repository.deleteSettlement(it) } }, onEditExpense = { editActivityExpense(it, "nongroup") }, onDeleteExpense = { id -> task("Expense deleted", "Expense deleted", resetScroll = true) { repository.deleteExpense(id) } }, onEditSettlement = { editActivityPayment(it, "nongroup") }, onRestoreExpense = { id -> task("Expense restored", "Expense restored", resetScroll = true) { repository.restoreExpense(id) } })
                    "group_settings" -> if (group != null && !group.isNonGroup) GroupSettingsScreen(state, group, busy,
                        onUpdate = { name, theme -> task("Group settings saved") { repository.updateGroupDetails(group.id, name, theme) } },
                        onMembers = { membersReturn = "group_settings"; route = "members" },
                        onBalances = { groupTab = 1; route = "group" },
                        onSimplify = { task("Debt preference saved") { repository.setSimplifyDebts(group.id, it) } },
                        onDefaults = { route = "group_defaults" },
                        onVisibility = { visibility -> task { repository.setGroupVisibility(group.id, visibility); openRoot("groups") } })
                    "group_defaults" -> if (group != null && !group.isHidden) GroupDefaultsScreen(state, group, busy) { defaults -> task("Default split saved") { repository.setExpenseDefaults(group.id, defaults); route = "group_settings" } }
                    "members" -> if (membersGroup != null) GroupMembersScreen(if (membersGroup.id == NON_GROUP_ID) nonGroupState else state, membersGroup, busy, onAddMember = { openMembers() }, onRemoveMember = { id -> task("Member removed") { repository.removeGroupMember(membersGroup.id, id) } })
                    "member" -> if (group != null) MemberPickerScreen(
                        state = state, group = group, busy = busy, query = memberQuery,
                        selectedIds = selectedMemberIds, newNames = newMemberNames, review = memberReview,
                        onQuery = { memberQuery = it },
                        onToggle = { id -> selectedMemberIds = if (id in selectedMemberIds) selectedMemberIds - id else selectedMemberIds + id },
                        onAddName = { newMemberNames = newMemberNames + it },
                        onRemoveName = { newMemberNames = newMemberNames - it },
                        onReview = { memberReview = true }, onEdit = { memberReview = false },
                        onConfirm = { task("Members added", resetScroll = true) { repository.addMembers(group.id, selectedMemberIds, newMemberNames); route = memberReturn } }
                    )
                    "expense" -> if (group != null) ExpenseWizard(state, group, state.expenses.find { it.id == expenseId }, busy, expenseStep, { expenseStep = it }, onDirty = { expenseDirty = it }) { expense ->
                        val editing = expenseId.isNotBlank()
                        task(if (editing) "Expense updated" else "Expense added", if (editing) "Expense updated" else "Expense added", resetScroll = true) {
                        val saved = if (expense.groupId == NON_GROUP_ID) {
                            val participants = (expense.owed.keys + expense.paidBy.keys + expense.payerId + SELF_ID).distinct()
                            val target = repository.ensureNonGroupLedger(participants, requireNotNull(expense.currency))
                            expense.copy(groupId = target)
                        } else expense
                        repository.saveExpense(saved)
                        expenseDirty = false
                        if (editing) { groupId = saved.groupId; expenseId = saved.id; route = "detail" }
                        else {
                            groupId = if (state.groups.find { it.id == saved.groupId }?.isNonGroup == true || expense.groupId == NON_GROUP_ID) NON_GROUP_ID else saved.groupId
                            groupTab = 0; expenseId = ""; route = if (groupId == NON_GROUP_ID) "nongroup" else "group"
                        }
                        savedNotice = route to if (editing) "Expense updated" else "Expense added"
                    } }
                    "detail" -> if (group != null) state.expenses.find { it.id == expenseId }?.let { expense -> ExpenseDetail(state, group, expense, busy, onEdit = { expenseStep = 0; expenseDirty = false; route = "expense" }, onDelete = { task("Expense deleted", "Expense deleted", resetScroll = true) { repository.deleteExpense(expense.id) } }, onRestore = { task("Expense restored", "Expense restored", resetScroll = true) { repository.restoreExpense(expense.id) } }) }
                    "settlement" -> if (group != null) CompactSettlementForm(state, group, settlementFrom, settlementTo, settlementAmount, settlementCurrency, busy, existing = state.settlements.find { it.id == settlementEditId }) { settlement -> task(if (settlementEditId.isBlank()) "Payment recorded" else "Payment updated", if (settlementEditId.isBlank()) "Payment recorded" else "Payment updated", resetScroll = true) { repository.saveSettlement(settlement); settlementEditId = ""; route = detailReturn } }
                }
                }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                if (homeAddExpanded && route == "groups") Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f))
                        .testTag("home_add_menu_dismiss")
                        .semantics { contentDescription = "Close add menu" }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "Close add menu"
                        ) { homeAddExpanded = false }
                )
            }
        }
        SaveCelebration(celebration) { id -> if (celebration?.id == id) celebration = null }
        }
        if (addFriend) AlertDialog(onDismissRequest = { if (!busy) addFriend = false }, title = { Text("Add a friend") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Field(newFriendName, { newFriendName = it }, "Name", "friend_name"); Hint("Add a local person to share expenses with. No invitation is sent.") } }, confirmButton = { TextButton(onClick = { task(resetScroll = true) { val addedFriend = repository.addFriend(newFriendName); addFriend = false; requestedFriend = addedFriend; selectedRootTab = "friends" } }, enabled = !busy && newFriendName.trim().isNotBlank(), modifier = Modifier.testTag("friend_save")) { Text("Add friend") } }, dismissButton = { TextButton(onClick = { addFriend = false }, enabled = !busy) { Text("Cancel") } })
        if (nonGroupPayment) AlertDialog(onDismissRequest = { nonGroupPayment = false }, title = { Text("Choose a payment context") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Hint("Each payment is recorded against its original people and currency.")
            state.groups.filter { it.isNonGroup && settlementCurrency in Ledger.groupCurrencies(state, it.id) }.forEach { context ->
                TextButton(onClick = { settlementEditId = ""; groupId = context.id; settlementFrom = ""; settlementTo = ""; settlementAmount = ""; detailReturn = "nongroup"; nonGroupPayment = false; route = "settlement" }, modifier = Modifier.fillMaxWidth()) { Text("${context.memberIds.filter { it != SELF_ID }.joinToString { memberName(state, it) }.ifBlank { "Just you" }} · $settlementCurrency") }
            }
        } }, confirmButton = { TextButton(onClick = { nonGroupPayment = false }) { Text("Cancel") } })
        if (expenseChooser) {
            var query by rememberSaveable { mutableStateOf("") }
            val search = query.trim()
            val groups = state.groups.filter { !it.isNonGroup && !it.isHidden && it.name.contains(search, ignoreCase = true) }
            val friends = state.members.filter { it.id != SELF_ID && it.name.contains(search, ignoreCase = true) }
            val showNonGroup = "Non-group expenses".contains(search, ignoreCase = true)
            AlertDialog(
                onDismissRequest = { expenseChooser = false },
                title = { Text("Add expense with") },
                text = {
                    Column(Modifier.height(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search groups or friends", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = {
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.testTag("expense_destination_clear")) {
                                        Icon(Icons.Default.Close, "Clear search")
                                    }
                                }
                            },
                            singleLine = true, input = FieldInput.SEARCH,
                            modifier = Modifier.fillMaxWidth().testTag("expense_destination_search"),
                        )
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (groups.isNotEmpty()) Text("Groups", style = MaterialTheme.typography.labelLarge)
                            groups.forEach { candidate -> TextButton(onClick = { expenseChooser = false; groupId = candidate.id; groupParent = rootTab; groupTab = 0; expenseId = ""; expenseStep = 0; detailReturn = "group"; expenseDirty = false; route = "expense" }, modifier = Modifier.fillMaxWidth().testTag("choose_group_${candidate.id}")) { GroupBadge(candidate, Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)); Text(candidate.name, Modifier.weight(1f)) } }
                            if (showNonGroup) TextButton(onClick = { expenseChooser = false; newNonGroupExpense() }, modifier = Modifier.fillMaxWidth().testTag("choose_non_group")) { Text("Non-group expenses") }
                            if (friends.isNotEmpty()) Text("Friends", style = MaterialTheme.typography.labelLarge)
                            friends.forEach { person -> TextButton(onClick = { expenseChooser = false; friendId = person.id; newNonGroupExpense(person.id, "nongroup") }, modifier = Modifier.fillMaxWidth().testTag("choose_friend_${person.id}")) { Icon(Icons.Default.Person, null); Spacer(Modifier.width(12.dp)); Text(person.name, Modifier.weight(1f)) } }
                            if (groups.isEmpty() && friends.isEmpty() && !showNonGroup) Text("No groups or friends found.", modifier = Modifier.testTag("expense_destination_empty"))
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { expenseChooser = false; route = "create" }) { Text("New group") } },
                dismissButton = { TextButton(onClick = { expenseChooser = false; addFriend = true; newFriendName = "" }) { Text("New friend") } },
            )
        }
        if (confirmDiscardMembers) AlertDialog(
            onDismissRequest = { confirmDiscardMembers = false },
            title = { Text("Cancel adding members?") },
            text = { Text("The people you selected will not be added to this group.") },
            confirmButton = { TextButton(onClick = {
                confirmDiscardMembers = false
                selectedMemberIds = emptyList()
                newMemberNames = emptyList()
                memberQuery = ""
                leaveScreen()
            }, modifier = Modifier.testTag("member_discard")) { Text("Cancel adding") } },
            dismissButton = { TextButton(onClick = { confirmDiscardMembers = false }, modifier = Modifier.testTag("member_keep_editing")) { Text("Continue editing") } }
        )
        if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Discard unsaved expense?") }, text = { Text("Your changes have not been saved.") }, confirmButton = { TextButton(onClick = { confirmDiscard = false; expenseDirty = false; leaveScreen() }, modifier = Modifier.testTag("discard_expense")) { Text("Discard changes") } }, dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } })
    }
}
    }
    }
    if (navigationVisible) MaterialTheme(colorScheme = navigationColors) {
        FloatingBottomNavigation(
            selectedTab = selectedRootTab, enabled = navigationEnabled,
            onSelect = { tab ->
                if (navigationEnabled) {
                    shellFocus.clearFocus(force = true); shellKeyboard?.hide()
                    if (selectedRootTab == tab) requestedRoot = tab else selectedRootTab = tab
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { navigationHeight = it.height },
        )
    }
    }
}

@Composable private fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
@Composable private fun Eyebrow(text: String) { Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
@Composable private fun Hint(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun CardBlock(content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) } }
@Composable private fun Primary(text: String, tag: String = "", enabled: Boolean = true, click: () -> Unit) { Button(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(tag), shape = RoundedCornerShape(16.dp)) { Text(text, fontWeight = FontWeight.SemiBold) } }
@Composable private fun Field(value: String, change: (String) -> Unit, label: String, tag: String = "", numeric: Boolean = false, single: Boolean = true) { AppTextField(value = value, onValueChange = change, label = { Text(label) }, singleLine = single, input = if (numeric) FieldInput.DECIMAL else if (tag in setOf("profile_name", "settings_name", "friend_name", "member_new_name", "group_name")) FieldInput.NAME else FieldInput.SEARCH, modifier = Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(14.dp)) }
@Composable private fun MoneyText(amount: Long, currency: String, modifier: Modifier = Modifier) { Text(Ledger.formatMoney(amount, currency), modifier, fontWeight = FontWeight.SemiBold, color = if (amount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
private fun memberName(state: AppState, id: String): String = state.members.find { it.id == id }?.name ?: "Unknown member"
private fun dateText(at: Long): String = calendarDateLabel(at)
private fun plainMoneyAbs(minor: Long, currency: String): String = BigDecimal.valueOf(minor, currencyDigits(currency)).toPlainString().removePrefix("-")
private fun plainMoney(minor: Long, currency: String): String = BigDecimal.valueOf(minor, currencyDigits(currency)).toPlainString()

@Composable private fun WelcomeScreen(onContinue: () -> Unit) {
    Page {
        Spacer(Modifier.height(52.dp))
        ZeroitMark(Modifier.size(80.dp))
        ZeroitWordmark(color = MaterialTheme.colorScheme.primary)
        Text("Share fairly.\nStay connected.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Hint("Keep shared expenses clear, from a coffee run to a weekend away.")
        CardBlock { Text("Free and open source", style = MaterialTheme.typography.titleMedium); Hint("Create groups, split expenses, and keep track of who owes whom. No ads or paid unlocks.") }
        Primary("Get started", "welcome_continue", click = onContinue)
        Hint("This version stores your ledger on this device. No account or sign-in is required.")
    }
}

@Composable private fun ProfileScreen(busy: Boolean, initial: String, onBack: () -> Unit, onContinue: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(if (initial == "You") "" else initial) }
    Page {
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.testTag("profile_back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("Back") }
        Eyebrow("First, a quick introduction")
        Text("What should we call you?", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Hint("Your name appears in groups, expenses, and balances.")
        CardBlock { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary); Text("Your device. Your ledger.", style = MaterialTheme.typography.titleMedium); Hint("This first version works offline on this device. Members are local names; accounts and shared sync are coming later.") }
        Field(name, { name = it }, "What should we call you?", "profile_name")
        Primary("Let's get started", "profile_continue", !busy && name.trim().isNotEmpty()) { onContinue(name.trim()) }
        Hint("Free and open source. No ads or paid unlocks.")
    }
}

@Composable private fun SearchField(visible: Boolean, query: String, onQuery: (String) -> Unit, tag: String = "home_search") {
    if (visible) AppTextField(value = query, onValueChange = onQuery, placeholder = { Text("Search") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, input = FieldInput.SEARCH, modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable private fun OverallBalance(presentation: HomeLedgerPresentation) {
    val totals = presentation.overallBalances
    val measurer = rememberTextMeasurer()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (totals.isEmpty()) Text("You're all settled up!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        else totals.entries.sortedBy { it.key }.forEach { (currency, amount) ->
            val label = if (amount.signum() > 0) "Overall, you are owed" else "Overall, you owe"
            val value = moneyText(amount.abs(), currency)
            val labelStyle = MaterialTheme.typography.bodyMedium
            val amountStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
            val color = if (amount.signum() < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("overall_balance_$currency")) {
                val needed = measurer.measure(AnnotatedString("$label "), labelStyle).size.width + measurer.measure(AnnotatedString(value), amountStyle).size.width
                if (needed > constraints.maxWidth) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = labelStyle)
                    Text(value, style = amountStyle, color = color, modifier = Modifier.fillMaxWidth().testTag("overall_amount_$currency"))
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = labelStyle)
                    Text(value, style = amountStyle, color = color, modifier = Modifier.testTag("overall_amount_$currency"))
                }
            }
        }
    }
}

@Composable private fun Avatar(name: String, group: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = if (group) RoundedCornerShape(12.dp) else CircleShape, modifier = Modifier.size(52.dp)) {
        Box(contentAlignment = Alignment.Center) { if (group) Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) else Text(name.take(1).uppercase(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

@Composable private fun BalanceLines(balances: Map<String, Long>, settled: String = "settled up", receivableLabel: String = "owes you", modifier: Modifier = Modifier, alignment: Alignment.Horizontal = Alignment.End) {
    val nonzero = balances.filterValues { it != 0L }
    // A single bounded column prevents each currency from competing as a separate
    // Row child and consuming all space reserved for the group or friend's name.
    Column(modifier, horizontalAlignment = alignment, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (nonzero.isEmpty()) Text(settled, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else nonzero.entries.sortedBy { it.key }.forEach { (currency, amount) ->
            Column(horizontalAlignment = alignment) {
                val color = groupBalanceColor(amount)
                Text(if (amount > 0) receivableLabel else "you owe", style = MaterialTheme.typography.labelSmall, color = color)
                Text(moneyText(BigInteger.valueOf(amount).abs(), currency), style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun LedgerSummaryRow(name: String, subtitle: String?, balances: Map<String, Long>, settled: String, receivableLabel: String = "owes you", modifier: Modifier = Modifier, leading: @Composable () -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        val balanceWidth = maxWidth * .45f
        val stack = LocalDensity.current.fontScale > 1.3f
        @Composable fun NameBlock(nameModifier: Modifier) {
            Column(nameModifier) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (stack) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { leading(); NameBlock(Modifier.weight(1f)) }
            BalanceLines(balances, settled, receivableLabel, Modifier.fillMaxWidth(), Alignment.Start)
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            leading()
            NameBlock(Modifier.weight(1f))
            BalanceLines(balances, settled, receivableLabel, Modifier.widthIn(max = balanceWidth))
        }
    }
}

@Composable private fun GroupsScreen(state: AppState, presentation: HomeLedgerPresentation, busy: Boolean, search: Boolean, query: String, onQuery: (String) -> Unit, filter: BalanceFilter, sort: BalanceSort, sortCurrency: String, onCreate: () -> Unit, onGroup: (String) -> Unit, onDemo: () -> Unit, onNonGroup: () -> Unit) {
    Page(Modifier.testTag("groups_screen")) {
        SearchField(search, query, onQuery)
        OverallBalance(presentation)
        val candidateGroups = state.groups.filterNot { it.isNonGroup || it.isHidden }.filter { it.name.contains(query.trim(), true) }
        val personalState = presentation.nonGroupState
        val showPersonal = query.isBlank() || "Non-group expenses".contains(query, true) || state.expenses.any { expense -> state.groups.any { it.id == expense.groupId && it.isNonGroup } && (expense.title.contains(query, true) || expense.owed.keys.any { memberName(state, it).contains(query, true) }) }
        val items = candidateGroups.map { group -> presentation.groupItemsById.getValue(group.id) } +
            if (showPersonal) listOf(presentation.nonGroupItem) else emptyList()
        val ordered = homeBalanceItems(items, filter, sort, sortCurrency)
        HomeBalanceSummary(filter, sort, sortCurrency)
        if (ordered.isEmpty()) {
            Spacer(Modifier.height(24.dp)); Icon(Icons.Default.Groups, null, Modifier.size(72.dp).align(Alignment.CenterHorizontally), tint = MaterialTheme.colorScheme.primary)
            Text(if (filter != BalanceFilter.ALL) "No groups match this filter" else if (query.isBlank()) "Share expenses with a group" else "No matching groups", style = MaterialTheme.typography.titleLarge)
            Hint("Groups make trips, households, and everyday expenses easier to keep track of.")
            OutlinedButton(onClick = onCreate, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("create_group_empty")) { Text("Start a new group") }
            if (state.groups.isEmpty()) TextButton(onClick = onDemo, enabled = !busy) { Text("Explore with sample data") }
        }
        ordered.forEach { item ->
            val personal = item.id == NON_GROUP_ID
            val group = candidateGroups.firstOrNull { it.id == item.id }
            LedgerSummaryRow(
                name = item.name,
                subtitle = if (personal) "${personalState.expenses.count { !it.deleted }} transactions · individual expenses" else "${group!!.activeMemberIds.size} ${if (group.activeMemberIds.size == 1) "member" else "members"}",
                balances = item.balances, settled = if (item.hasDebt || item.hasReceivable) "payments remain" else "settled up", receivableLabel = "you are owed",
                modifier = Modifier.clickable { if (personal) onNonGroup() else onGroup(item.id) }.testTag(if (personal) "non_group_expenses" else "group_${item.id}"),
                leading = { if (group != null) GroupBadge(group) else Avatar("Non-group expenses", true) },
            )
        }
        Spacer(Modifier.height(84.dp))
    }
}

@Composable private fun FriendsScreen(state: AppState, presentation: HomeLedgerPresentation, search: Boolean, query: String, onQuery: (String) -> Unit, filter: BalanceFilter, sort: BalanceSort, sortCurrency: String, onFriend: (String) -> Unit, onAdd: () -> Unit) {
    Page(Modifier.testTag("friends_screen")) {
        SearchField(search, query, onQuery, "friend_search"); OverallBalance(presentation)
        val people = presentation.friendItems
        val ordered = homeBalanceItems(people.filter { it.name.contains(query.trim(), true) }, filter, sort, sortCurrency)
        HomeBalanceSummary(filter, sort, sortCurrency)
        if (ordered.isEmpty()) { Spacer(Modifier.height(24.dp)); Text(if (filter != BalanceFilter.ALL) "No friends match this filter" else if (query.isBlank()) "Keep track with friends" else "No matching friends", style = MaterialTheme.typography.titleLarge); Hint("Add a person to split expenses directly, or find the people already in your groups here."); OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add a friend") } }
        ordered.forEach { person -> LedgerSummaryRow(person.name, null, person.balances,
            settled = if (person.hasDebt || person.hasReceivable) "payments remain" else if (state.expenses.none { !it.deleted && (it.payerId == person.id || person.id in it.owed) }) "no expenses" else "settled up",
            modifier = Modifier.clickable { onFriend(person.id) }.testTag("friend_${person.id}"), leading = { Avatar(person.name) }) }
        Spacer(Modifier.height(84.dp))
    }
}

@Composable private fun FriendScreen(state: AppState, friendId: String, busy: Boolean, onAdd: () -> Unit, onExpense: (String) -> Unit, onGroup: (String) -> Unit, onSettle: (String, String) -> Unit) {
    val person = state.members.firstOrNull { it.id == friendId } ?: return
    val contexts = state.groups.filter { friendId in it.memberIds && SELF_ID in it.memberIds }
    Page(Modifier.testTag("friend_screen")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(person.name); Column(Modifier.weight(1f)) { Text(person.name, style = MaterialTheme.typography.headlineSmall); BalanceLines(Ledger.friendBalances(state, friendId), "You are settled up") } }
        Primary("Add expense", "friend_add_expense", !busy, onAdd)
        val directGroups = contexts.filter { it.isNonGroup }
        contexts.forEach { group ->
            Ledger.friendBalances(state.copy(groups = listOf(group)), friendId).filterValues { it != 0L }.forEach { (currency, _) ->
                OutlinedButton(onClick = { onSettle(group.id, currency) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("friend_settle_${group.id}${if (currency == group.currency) "" else "_$currency"}")) { Text("Settle up · ${if (group.isNonGroup) "Non-group expenses" else group.name} · $currency") }
            }
        }
        Text("Shared expenses", style = MaterialTheme.typography.titleMedium)
        contexts.filterNot { it.isNonGroup || it.isHidden }.forEach { shared -> LedgerSummaryRow(shared.name, "Shared group", Ledger.friendBalances(state.copy(groups = listOf(shared)), friendId), "settled up",
            modifier = Modifier.clickable { onGroup(shared.id) }.testTag("friend_group_${shared.id}").semantics { contentDescription = "Open ${shared.name}" }, leading = { GroupBadge(shared) }) }
        val expenses = state.expenses.filter { expense -> directGroups.any { it.id == expense.groupId } && (friendId in Ledger.expensePayers(expense) || friendId in expense.owed) && (SELF_ID in Ledger.expensePayers(expense) || SELF_ID in expense.owed) }.let(::expensesNewestAddedFirst)
        if (expenses.isEmpty() && contexts.none { !it.isNonGroup }) Hint("No shared expenses yet. Add your first expense with ${person.name}.")
        expenses.forEach { expense -> val group = contexts.first { it.id == expense.groupId }; ListItem(headlineContent = { Text(expense.title) }, supportingContent = { Text("${if (group.isNonGroup) "Non-group expense" else group.name} · ${dateText(expense.date)}${if (expense.deleted) " · Deleted" else ""}") }, trailingContent = { Text(Ledger.formatMoney(expense.amountMinor, Ledger.expenseCurrency(state, expense))) }, leadingContent = { Icon(Icons.Default.ReceiptLong, null) }, modifier = Modifier.clickable { onExpense(expense.id) }) }
        val settlements = state.settlements.filter { !it.deleted && contexts.any { g -> g.id == it.groupId } && setOf(it.fromId, it.toId) == setOf(SELF_ID, friendId) }.sortedByDescending { it.date }
        if (settlements.isNotEmpty()) Text("Recorded payments", style = MaterialTheme.typography.titleMedium)
        settlements.forEach { settlement -> val group = contexts.first { it.id == settlement.groupId }; ListItem(headlineContent = { Text("${memberName(state, settlement.fromId)} paid ${memberName(state, settlement.toId)}") }, supportingContent = { Text(dateText(settlement.date)) }, trailingContent = { Text(Ledger.formatMoney(settlement.amountMinor, Ledger.settlementCurrency(state, settlement))) }) }
    }
}

@Composable private fun GlobalActivityScreen(state: AppState, busy: Boolean, onContext: (String) -> Unit, onEditExpense: (String) -> Unit, onDeleteExpense: (String) -> Unit, onEditSettlement: (String) -> Unit, onDeleteSettlement: (String) -> Unit, onRestoreExpense: (String) -> Unit) {
    val activities = remember(state.activity) { state.activity.asReversed().sortedByDescending { it.at } }
    val groups = remember(state.groups) { state.groups.associateBy { it.id } }
    LazyColumn(modifier = Modifier.fillMaxSize().imePadding().testTag("activity_screen"),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (activities.isEmpty()) item(key = "activity-empty") {
            Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.NotificationsNone, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text("No activity yet", style = MaterialTheme.typography.titleLarge)
                Hint("New groups, expenses, and recorded payments will appear here.")
            }
        }
        items(activities, key = { it.id }) { activity ->
            val group = groups[activity.groupId]
            ActivityEntryCard(state, activity, busy, onEditExpense, onDeleteExpense, contextLabel = group?.name,
                onContext = group?.let { { onContext(it.id) } }, onEditSettlement = onEditSettlement, onDeleteSettlement = onDeleteSettlement, onRestoreExpense = onRestoreExpense)
        }
    }
}

@Composable private fun CreateGroupScreen(profile: String, busy: Boolean, save: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("USD") }
    Page {
        Eyebrow("Bring your people together")
        Hint("Give your group a name. You'll choose the people to add after creating it.")
        Field(name, { name = it }, "Group name", "group_name")
        Choice("Default currency", listOf("USD", "EUR", "GBP", "INR", "JPY", "CAD", "AUD"), currency) { currency = it }
        CardBlock { Text("You're the first member", style = MaterialTheme.typography.titleMedium); Hint("$profile is included automatically. Members can join your local group one at a time or together.") }
        Primary("Create group", "group_save", !busy && name.trim().isNotEmpty()) { save(name.trim(), currency) }
        Hint("New expenses start in this currency. Change it for any expense; balances stay separate.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun MemberPickerScreen(
    state: AppState, group: Group, busy: Boolean, query: String,
    selectedIds: List<String>, newNames: List<String>, review: Boolean,
    onQuery: (String) -> Unit, onToggle: (String) -> Unit, onAddName: (String) -> Unit,
    onRemoveName: (String) -> Unit, onReview: () -> Unit, onEdit: () -> Unit, onConfirm: () -> Unit
) {
    var addingName by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf("") }
    val candidates = state.members.filter { it.id != SELF_ID && it.id !in group.activeMemberIds }.sortedBy { it.name.lowercase() }
    val selected = candidates.filter { it.id in selectedIds }
    val count = selectedIds.size + newNames.size
    Page {
        Eyebrow(group.name)
        if (review) {
            Text("Add $count ${if (count == 1) "person" else "people"}?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Hint("Review your selection before adding everyone to this group.")
            CardBlock {
                selected.forEach { person ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(person.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onToggle(person.id) }, enabled = !busy, modifier = Modifier.testTag("member_review_remove_${person.id}")) { Text("Remove") }
                    }
                }
                newNames.forEachIndexed { index, person ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(person, fontWeight = FontWeight.Medium); Hint("New local person") }
                        TextButton(onClick = { onRemoveName(person) }, enabled = !busy, modifier = Modifier.testTag("member_review_remove_new_$index")) { Text("Remove") }
                    }
                }
            }
            Primary("Add to group", "member_confirm", !busy && count > 0, onConfirm)
            OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("member_edit_selection")) { Text("Edit selection") }
            Hint("Existing expenses keep their original participants. No invitations or messages are sent.")
        } else {
            Hint("Choose several people, or add a new person by name. Everyone is added together after you review.")
            Field(query, onQuery, "Search people", "member_search")
            OutlinedButton(onClick = { name = query.trim(); nameError = ""; addingName = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("member_new")) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("Add a new person") }
            if (count > 0) {
                Text("Selected · $count", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    selected.forEach { person -> InputChip(selected = true, onClick = { onToggle(person.id) }, enabled = !busy, label = { Text(person.name) }, trailingIcon = { Icon(Icons.Default.Close, "Remove ${person.name}", Modifier.size(16.dp)) }, modifier = Modifier.testTag("member_remove_${person.id}")) }
                    newNames.forEachIndexed { index, person -> InputChip(selected = true, onClick = { onRemoveName(person) }, enabled = !busy, label = { Text(person) }, trailingIcon = { Icon(Icons.Default.Close, "Remove $person", Modifier.size(16.dp)) }, modifier = Modifier.testTag("member_remove_new_$index")) }
                }
            }
            Text("People from your groups", style = MaterialTheme.typography.titleMedium)
            val filtered = candidates.filter { it.name.contains(query.trim(), ignoreCase = true) }
            if (filtered.isEmpty()) Hint(if (candidates.isEmpty()) "People you add will be available to select in your other groups." else "No matching people. Try another name or add someone new.")
            filtered.forEach { person ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { onToggle(person.id) }.padding(vertical = 8.dp).testTag("member_select_${person.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = person.id in selectedIds, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp)); Text(person.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Primary(if (count == 0) "Select people to continue" else "Review $count ${if (count == 1) "person" else "people"}", "member_next_footer", !busy && count > 0, onReview)
            Hint("Members are local people on this device. Account invitations and shared sync are not available yet.")
        }
    }
    if (addingName) AlertDialog(
        onDismissRequest = { addingName = false },
        title = { Text("Add a new person") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("Add one person at a time. You'll review everyone together before saving.")
            Field(name, { name = it; nameError = "" }, "Name", "member_new_name")
            if (nameError.isNotEmpty()) Text(nameError, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = {
            val trimmed = name.trim()
            val existing = state.members.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            when {
                trimmed.isBlank() -> nameError = "Enter a name."
                trimmed.length > 120 -> nameError = "Use a name of 120 characters or fewer."
                newNames.any { it.equals(trimmed, ignoreCase = true) } -> nameError = "This person is already selected."
                existing != null && existing.id in group.activeMemberIds -> nameError = "This person is already in the group."
                existing != null -> { if (existing.id !in selectedIds) onToggle(existing.id); onQuery(""); addingName = false }
                else -> { onAddName(trimmed); onQuery(""); addingName = false }
            }
        }, enabled = !busy && name.trim().isNotEmpty(), modifier = Modifier.testTag("member_new_add")) { Text("Add to selection") } },
        dismissButton = { TextButton(onClick = { addingName = false }) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Choice(label: String, options: List<String>, value: String, select: (String) -> Unit) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(label, style = MaterialTheme.typography.labelLarge); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { option -> FilterChip(selected = value == option, onClick = { select(option) }, label = { Text(option) }) } } } }


@Composable private fun ExpenseDetail(state: AppState, group: Group, expense: Expense, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onRestore: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val currency = Ledger.expenseCurrency(state, expense)
    Page { Eyebrow(if (expense.deleted) "Deleted expense" else group.name); Text(expense.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold); Text(Ledger.formatMoney(expense.amountMinor, Ledger.expenseCurrency(state, expense)), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary); Hint("$currency · ${dateText(expense.date)}"); CardBlock { Text("Who paid", style = MaterialTheme.typography.titleMedium); Ledger.expensePayers(expense).forEach { (id, paid) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(memberName(state, id), Modifier.weight(1f)); MoneyText(paid, currency) } } }; CardBlock { Text("Who owes what", style = MaterialTheme.typography.titleMedium); expense.owed.forEach { (id, owed) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(memberName(state, id), Modifier.weight(1f)); MoneyText(owed, currency) } } }; if (expense.notes.isNotBlank()) CardBlock { Text("Notes", style = MaterialTheme.typography.titleMedium); Text(expense.notes) }; if (expense.deleted) { Hint("This expense is excluded from balances. Restore it to include its shares again."); Primary("Restore expense", "expense_restore", !busy, onRestore) } else { Primary("Edit expense", "expense_edit", !busy, onEdit); OutlinedButton(onClick = { confirm = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("expense_delete")) { Text("Delete expense", color = MaterialTheme.colorScheme.error) } } }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete this expense?") }, text = { Text("It will be excluded from balances. You can restore it from this group later.") }, confirmButton = { TextButton(onClick = { confirm = false; onDelete() }, modifier = Modifier.testTag("confirm_delete")) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable private fun SettingsScreen(state: AppState, busy: Boolean, onName: (String) -> Unit, onTheme: (String) -> Unit, onDemo: () -> Unit, onRestore: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(state.profileName) }
    var requestedName by remember { mutableStateOf<String?>(null) }
    var nameSaved by remember { mutableStateOf(false) }
    val profileFocus = LocalFocusManager.current
    val profileKeyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.profileName) { if (requestedName == state.profileName) { profileFocus.clearFocus(force = true); profileKeyboard?.hide(); name = state.profileName; nameSaved = true; requestedName = null } }
    Page {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(state.profileName)
            Column { Text(state.profileName, style = MaterialTheme.typography.titleLarge); Hint("Local profile") }
        }
        Field(name, { name = it; nameSaved = false }, "Your name", "settings_name")
        Primary("Save name", "settings_save", !busy && name.trim().isNotEmpty() && name.trim() != state.profileName) { requestedName = name.trim(); onName(name.trim()) }
        if (nameSaved) Text("Name saved", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("settings_name_saved").semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        Eyebrow("Preferences")
        CardBlock {
            Text("Appearance", style = MaterialTheme.typography.titleLarge)
            listOf("SYSTEM" to "System default", "LIGHT" to "Light", "DARK" to "Dark").forEach { (mode, label) ->
                Row(Modifier.fillMaxWidth().testTag("theme_${mode.lowercase()}").clickable(enabled = !busy) { onTheme(mode) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.themeMode == mode, onClick = { onTheme(mode) }, enabled = !busy); Text(label)
                }
            }
        }
        CardBlock {
            Text("Explore demo scenarios", style = MaterialTheme.typography.titleMedium)
            Hint("Add five sample groups and a friend ledger, with shared bills, travel, settlements and an empty group. Your existing data stays. Adding again will not duplicate them.")
            OutlinedButton(onClick = onDemo, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("add_demo_scenarios")) { Text("Add demo groups and expenses") }
        }
        if (state.groups.any { it.isHidden }) CardBlock {
            Text("Hidden groups", style = MaterialTheme.typography.titleMedium)
            Hint("History is retained. Restore a group before editing it.")
            state.groups.filter { it.isHidden }.forEach { hidden ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GroupBadge(hidden)
                    Column(Modifier.weight(1f)) { Text(hidden.name); Hint(if (hidden.visibility == GroupVisibility.LEFT) "Left on this device" else "Deleted · recoverable") }
                    TextButton(onClick = { onRestore(hidden.id) }, enabled = !busy, modifier = Modifier.testTag("restore_group_${hidden.id}")) { Text("Restore") }
                }
            }
        }
        CardBlock { Text("ZeroIT · Local edition", style = MaterialTheme.typography.titleMedium); Hint("Free, open source and built for clarity. Groups, expenses and names are stored on this device. This version has no account, shared sync or money transfers.") }
    }
}

/** Display-only limit; full names remain stored and accessible in group settings. */
internal fun compactGroupTitle(name: String): String {
    if (name.length <= 24) return name
    var prefix = name.take(23).trimEnd()
    if (prefix.lastOrNull()?.isHighSurrogate() == true) prefix = prefix.dropLast(1)
    return "$prefix…"
}
