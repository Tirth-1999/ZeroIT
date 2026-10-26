import XCTest

final class FairshareUITests: XCTestCase {
    /// Physical viewport evidence, using only a fresh Debug-selected ledger.
    func testIsolatedFooterGeometryAcrossRotation() {
        continueAfterFailure = false
        let device = XCUIDevice.shared
        let originalOrientation = device.orientation
        defer { device.orientation = originalOrientation }
        device.orientation = .portrait
        let app = XCUIApplication()
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = "fairshare-ui-test-" + UUID().uuidString + ".db"
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 20), "Expected isolated storage; refusing writes.")
        completeOnboarding(app)

        func captureGeometry(_ name: String, fabTag: String? = nil) {
            let capsule = tagged(app, "floating_bottom_navigation")
            XCTAssertTrue(capsule.waitForExistence(timeout: 5))
            waitForStationaryFrame(capsule, in: app)
            let viewport = app.frame
            let track = tagged(app, "floating_navigation_track").frame
            let shell = capsule.frame
            func valid(_ frame: CGRect) -> Bool {
                [frame.minX, frame.minY, frame.width, frame.height].allSatisfy { $0.isFinite } && frame.width > 0 && frame.height > 0
            }
            XCTAssertTrue(valid(viewport) && valid(shell) && valid(track))
            XCTAssertTrue(viewport.insetBy(dx: -1, dy: -1).contains(shell), "Capsule outside application viewport")
            XCTAssertLessThanOrEqual(shell.width, 601)
            XCTAssertEqual(shell.midX, viewport.midX, accuracy: 1, "Capsule must remain centered")
            XCTAssertTrue(shell.insetBy(dx: -1, dy: -1).contains(track))
            var measurements = ["viewport: \(viewport)", "capsule: \(shell)", "track: \(track)",
                "capsule bottom gap: \(viewport.maxY - shell.maxY)",
                "Native bottom safeAreaInsets is not exposed by this XCTest interface; not measured."]
            var previous: CGRect?
            for tab in ["groups", "friends", "activity", "account"] {
                let frame = tagged(app, "nav_" + tab).frame
                XCTAssertTrue(valid(frame))
                XCTAssertGreaterThanOrEqual(frame.width, 48)
                XCTAssertGreaterThanOrEqual(frame.height, 48)
                XCTAssertTrue(viewport.insetBy(dx: -1, dy: -1).contains(frame))
                XCTAssertTrue(shell.insetBy(dx: -1, dy: -1).contains(frame))
                if let previous = previous { XCTAssertLessThanOrEqual(previous.maxX, frame.minX + 1) }
                previous = frame
                measurements.append("\(tab): \(frame); bottom gap: \(viewport.maxY - frame.maxY)")
            }
            if let fabTag = fabTag {
                let fab = tagged(app, fabTag)
                XCTAssertTrue(fab.exists)
                XCTAssertTrue(viewport.insetBy(dx: -1, dy: -1).contains(fab.frame))
                XCTAssertLessThanOrEqual(fab.frame.maxY, shell.minY, "FAB must clear the capsule")
                measurements.append("FAB: \(fab.frame); clearance: \(shell.minY - fab.frame.maxY)")
            }
            let report = XCTAttachment(string: measurements.joined(separator: "\n"))
            report.name = name + " — measured frames"
            report.lifetime = .keepAlways
            add(report)
            // Full-screen capture avoids XCTest's stale portrait app crop after
            // rotating the connected phone; geometry still uses current AUT frames.
            let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            screenshot.name = name
            screenshot.lifetime = .keepAlways
            add(screenshot)
        }

        func touchTab(_ tab: String, destination: String) {
            tagged(app, "nav_" + tab).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            XCTAssertTrue(tagged(app, destination).waitForExistence(timeout: 5))
            XCTAssertTrue(tagged(app, "nav_" + tab).isSelected)
        }

        captureGeometry("Footer — portrait Groups", fabTag: "home_add_menu")
        touchTab("friends", destination: "friends_screen")
        captureGeometry("Footer — portrait Friends", fabTag: "global_add_expense")
        touchTab("activity", destination: "activity_screen")
        captureGeometry("Footer — portrait Activity", fabTag: "global_add_expense")
        touchTab("account", destination: "settings_name")
        tap(app, "theme_dark")
        captureGeometry("Footer — portrait dark Account")
        // The final Account paragraph must scroll fully above the floating bar.
        // This checks content reservation without touching the normal ledger.
        for _ in 0..<3 { app.swipeUp() }
        let finalContent = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Free, open source and built for clarity.")).firstMatch
        XCTAssertTrue(finalContent.waitForExistence(timeout: 5))
        waitForStationaryFrame(finalContent, in: app)
        XCTAssertLessThanOrEqual(finalContent.frame.maxY, tagged(app, "floating_bottom_navigation").frame.minY,
            "Final scrollable content must remain above the floating footer")
        captureGeometry("Footer — final Account content clearance")
        device.orientation = .landscapeLeft
        let landscape = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.frame.width > app.frame.height }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [landscape], timeout: 8), .completed)
        captureGeometry("Footer — landscape dark Account")
        touchTab("friends", destination: "friends_screen")
        captureGeometry("Footer — landscape Friends", fabTag: "global_add_expense")
        device.orientation = .portrait
        let portrait = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.frame.height > app.frame.width }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [portrait], timeout: 8), .completed)
        touchTab("account", destination: "settings_name")
        tap(app, "theme_light")
        touchTab("groups", destination: "groups_screen")
        captureGeometry("Footer — restored portrait light Groups", fabTag: "home_add_menu")
    }

    private func verifySoftwareKeyboard(_ app: XCUIApplication, _ tag: String, decimal: Bool = false) {
        focusField(app, tag)
        let keyboard = app.keyboards.firstMatch
        XCTAssertTrue(keyboard.waitForExistence(timeout: 5), "No software keyboard for " + tag)
        XCTAssertTrue(app.frame.intersects(keyboard.frame), "Keyboard is off screen for " + tag)
        let character = decimal ? "1" : "a"
        let key = keyboard.keys.matching(NSPredicate(format: "label ==[c] %@", character)).firstMatch
        XCTAssertTrue(key.waitForExistence(timeout: 3), "Wrong keyboard type for " + tag)
        XCTAssertTrue(app.frame.contains(CGPoint(x: key.frame.midX, y: key.frame.midY)), "Key is off screen for " + tag)
        if decimal {
            XCTAssertFalse(keyboard.keys["q"].exists, "Amount must use a numeric keyboard: " + tag)
            XCTAssertTrue(keyboard.keys["."].exists || keyboard.keys[","].exists, "Decimal separator missing: " + tag)
        }
        let field = tagged(app, tag)
        let previous = field.value as? String ?? field.label
        key.tap()
        let changed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (field.value as? String ?? field.label) != previous
        }, object: field)
        XCTAssertEqual(XCTWaiter.wait(for: [changed], timeout: 3), .completed, "Visible key did not enter text into " + tag)
        let capture = XCTAttachment(screenshot: app.screenshot())
        capture.name = "Software keyboard — " + tag
        capture.lifetime = .keepAlways
        add(capture)
    }

    /// Native drags exercise the bar's real pointer path. Fixture onboarding
    /// uses isolated storage; these checks do not alter the normal phone ledger.
    func testIsolatedLiquidTabDraggingCommitsOnReleaseAndIgnoresVerticalMotion() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = "fairshare-ui-test-" + UUID().uuidString + ".db"
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 20), "Expected isolated storage; refusing writes.")
        completeOnboarding(app)
        tap(app, "create_group")
        let groupName = tagged(app, "group_name")
        if !groupName.waitForExistence(timeout: 3) && tagged(app, "create_group").exists {
            // A real notification banner intercepted this fixture's first tap
            // on the connected phone. Wait for it to leave without opening or
            // dismissing the user's notification; retry only while still on the
            // original page, so this cannot create a duplicate group.
            let banner = XCUIApplication(bundleIdentifier: "com.apple.springboard")
                .descendants(matching: .any).matching(identifier: "NotificationShortLookView").firstMatch
            if banner.exists {
                let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: banner)
                XCTAssertEqual(XCTWaiter.wait(for: [gone], timeout: 15), .completed,
                    "System notification still covers group creation; app interaction was not attempted.")
            }
            tap(app, "create_group")
        }
        XCTAssertTrue(groupName.waitForExistence(timeout: 5), "Group form must be visible before entering the isolated fixture.")
        enter(app, "group_name", "Liquid navigation")
        tap(app, "group_save")
        tap(app, "group_tab_totals")
        XCTAssertTrue(tagged(app, "floating_bottom_navigation").waitForExistence(timeout: 5))
        let before = XCTAttachment(screenshot: app.screenshot())
        before.name = "Liquid navigation — before native drag"
        before.lifetime = .keepAlways
        add(before)

        func dragTab(from: String, to: String, destination: String) {
            let start = tagged(app, "nav_" + from).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            let end = tagged(app, "nav_" + to).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            start.press(forDuration: 0.08, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.1)
            let committed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                self.tagged(app, "nav_" + to).isSelected && self.tagged(app, destination).exists
            }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [committed], timeout: 3), .completed,
                "Native drag release must select " + to + " and show the associated page")
        }
        dragTab(from: "groups", to: "account", destination: "settings_name")
        dragTab(from: "account", to: "friends", destination: "friends_screen")
        dragTab(from: "friends", to: "groups", destination: "group_title")
        XCTAssertEqual(tagged(app, "group_title").label, "Liquid navigation")
        XCTAssertTrue(tagged(app, "group_tab_totals").isSelected)

        // Attempting to scroll upward from the Groups touch target must not
        // change the committed tab or navigate to another page.
        let groups = tagged(app, "nav_groups")
        let origin = groups.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let above = origin.withOffset(CGVector(dx: 0, dy: -120))
        origin.press(forDuration: 0.08, thenDragTo: above, withVelocity: .slow, thenHoldForDuration: 0.1)
        XCTAssertTrue(tagged(app, "nav_groups").isSelected)
        XCTAssertEqual(tagged(app, "group_title").label, "Liquid navigation")
        XCTAssertTrue(tagged(app, "group_tab_totals").isSelected,
            "A vertical motion must not turn into a Groups reselection and reset the saved subpage")
        let final = XCTAttachment(screenshot: app.screenshot())
        final.name = "Liquid navigation — native drag roundtrip and vertical rejection"
        final.lifetime = .keepAlways
        add(final)
    }

    /// Native coordinate taps exercise touch dispatch and saved destinations on the
    /// connected phone. Elapsed checks bound UI readiness, not rendered frame rate.
    func testIsolatedTouchTabsRespondPromptlyAndRestoreTotals() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = "fairshare-ui-test-" + UUID().uuidString + ".db"
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 20), "Expected isolated storage; refusing writes.")
        completeOnboarding(app)
        tap(app, "create_group")
        enter(app, "group_name", "Touch navigation") // Fixture setup; no typing assertion in this test.
        tap(app, "group_save")
        tap(app, "group_tab_totals")

        var timings: [String] = []
        func touchTab(_ tab: String, destination: String) {
            let control = tagged(app, "nav_" + tab)
            XCTAssertTrue(control.exists)
            let coordinate = control.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            let start = Date()
            coordinate.tap()
            let visible = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                let target = self.tagged(app, destination)
                return target.exists && control.isSelected
            }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [visible], timeout: 2), .completed,
                "Touch must select " + tab + " and show its destination promptly")
            let elapsed = Date().timeIntervalSince(start)
            timings.append(String(format: "%@: %.3f seconds", tab, elapsed))
            XCTAssertLessThanOrEqual(elapsed, 2.0, "Native touch-to-destination readiness exceeded two seconds")
        }
        touchTab("friends", destination: "friends_screen")
        touchTab("activity", destination: "activity_screen")
        touchTab("account", destination: "settings_name")
        touchTab("groups", destination: "group_title")
        XCTAssertEqual(tagged(app, "group_title").label, "Touch navigation")
        XCTAssertTrue(tagged(app, "group_tab_totals").isSelected, "Switching tabs must restore the group subpage")

        let light = XCTAttachment(screenshot: app.screenshot())
        light.name = "Touch roundtrip — restored group Totals"
        light.lifetime = .keepAlways
        add(light)

        // Cache visible locations, then send consecutive real screen touches with
        // no sleeps or destination waits between them. XCTest still synchronizes
        // each event; this deliberately does not claim simultaneous input.
        let coordinates = ["friends", "activity", "account", "groups"].map {
            tagged(app, "nav_" + $0).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        }
        coordinates.forEach { $0.tap() }
        XCTAssertTrue(tagged(app, "group_title").waitForExistence(timeout: 2))
        XCTAssertTrue(tagged(app, "nav_groups").isSelected)
        XCTAssertTrue(tagged(app, "group_tab_totals").isSelected)
        XCTAssertEqual(tagged(app, "group_title").label, "Touch navigation")
        let final = XCTAttachment(screenshot: app.screenshot())
        final.name = "Consecutive touch sequence — final saved destination"
        final.lifetime = .keepAlways
        add(final)
        let report = XCTAttachment(string: timings.joined(separator: "\n"))
        report.name = "Native touch-to-destination readiness (includes XCTest event overhead)"
        report.lifetime = .keepAlways
        add(report)
    }

    func testIsolatedFloatingTabsRestoreGroupAndEdgeBack() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = "fairshare-ui-test-" + UUID().uuidString + ".db"
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 20), "Expected isolated storage; refusing writes.")
        completeOnboarding(app)
        tap(app, "create_group")
        enter(app, "group_name", "Saved tab session")
        tap(app, "group_save")
        tap(app, "group_tab_totals")
        tap(app, "nav_friends")
        tap(app, "nav_groups")
        XCTAssertTrue(tagged(app, "group_tab_totals").isSelected)
        XCTAssertEqual(tagged(app, "group_title").label, "Saved tab session")
        let capture = XCTAttachment(screenshot: app.screenshot())
        capture.name = "Floating themed navigation — restored group Totals"
        capture.lifetime = .keepAlways
        add(capture)
        // Inward drags originate at the screen edge and use the finger's Y.
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: 2, dy: app.frame.height * 0.40)).press(forDuration: 0.08,
            thenDragTo: origin.withOffset(CGVector(dx: app.frame.width * 0.65, dy: app.frame.height * 0.40)),
            withVelocity: .slow, thenHoldForDuration: 0.1)
        let expenses = tagged(app, "group_tab_expenses")
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "selected == true"), object: expenses)
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 5), .completed)
        origin.withOffset(CGVector(dx: app.frame.width - 2, dy: app.frame.height * 0.45)).press(forDuration: 0.08,
            thenDragTo: origin.withOffset(CGVector(dx: app.frame.width * 0.35, dy: app.frame.height * 0.45)),
            withVelocity: .slow, thenHoldForDuration: 0.1)
        XCTAssertTrue(tagged(app, "groups_screen").waitForExistence(timeout: 5))
    }

    func testIsolatedExpenseChooserKeepsKeyboardAfterFirstCharacter() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = "fairshare-ui-test-" + UUID().uuidString + ".db"
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 20), "Expected isolated storage; refusing writes.")
        completeOnboarding(app)
        for name in ["Alpha trip", "Zulu trip"] {
            tap(app, "create_group")
            enter(app, "group_name", name)
            tap(app, "group_save")
            app.buttons["Back"].firstMatch.tap()
        }
        tap(app, "nav_friends")
        tap(app, "global_add_expense")
        let groupResults = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "choose_group_"))
        XCTAssertEqual(groupResults.count, 2, "Fixture must start with multiple matching destinations")
        focusField(app, "expense_destination_search")
        let field = tagged(app, "expense_destination_search")
        for (character, expected) in [("a", "a"), ("l", "al")] {
            // Deliberately do not focus the field again after its first character.
            // Tap native software keys, rather than injecting a replacement value.
            let keyboard = app.keyboards.firstMatch
            XCTAssertTrue(keyboard.waitForExistence(timeout: 5))
            XCTAssertTrue(app.frame.intersects(keyboard.frame), "Keyboard must remain on screen")
            let key = keyboard.keys.matching(NSPredicate(format: "label ==[c] %@", character)).firstMatch
            XCTAssertTrue(key.waitForExistence(timeout: 3))
            XCTAssertTrue(app.frame.contains(CGPoint(x: key.frame.midX, y: key.frame.midY)))
            key.tap()
            let entered = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                (field.value as? String ?? field.label).lowercased() == expected
            }, object: field)
            XCTAssertEqual(XCTWaiter.wait(for: [entered], timeout: 3), .completed)
            XCTAssertTrue(keyboard.exists && app.frame.intersects(keyboard.frame), "Typing must not dismiss the keyboard")
            XCTAssertEqual(groupResults.count, 1, "First character must change the results without ending input")
        }
        let capture = XCTAttachment(screenshot: app.screenshot())
        capture.name = "Friends expense chooser — consecutive software key taps"
        capture.lifetime = .keepAlways
        add(capture)
    }

    func testIsolatedInputKeyboardCoverage() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = "fairshare-ui-test-" + UUID().uuidString + ".db"
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 20), "Expected isolated storage; refusing writes.")
        completeOnboarding(app)
        app.buttons["Search groups"].tap()
        verifySoftwareKeyboard(app, "home_search")
        app.buttons["Search groups"].tap()
        tap(app, "create_group")
        verifySoftwareKeyboard(app, "group_name")
        tap(app, "group_save")
        tap(app, "group_members")
        tap(app, "add_members")
        verifySoftwareKeyboard(app, "member_search")
        tap(app, "member_new")
        verifySoftwareKeyboard(app, "member_new_name")
        tap(app, "member_new_add")
        tap(app, "member_next")
        tap(app, "member_confirm")
        app.buttons["Back"].firstMatch.tap()

        tap(app, "group_settings")
        tap(app, "group_rename")
        verifySoftwareKeyboard(app, "group_settings_name")
        app.buttons["Cancel"].tap()
        tap(app, "group_defaults")
        tap(app, "default_mode_EXACT")
        verifySoftwareKeyboard(app, "default_currency")
        for mode in ["EXACT", "PERCENT", "SHARES", "ADJUSTMENT"] {
            tap(app, "default_mode_" + mode)
            XCTAssertTrue(tagged(app, "default_mode_" + mode).isSelected, "Default split mode must actually change")
            verifySoftwareKeyboard(app, "default_value_self", decimal: true)
            if mode == "ADJUSTMENT" {
                app.buttons["Toggle positive or negative"].firstMatch.tap()
                XCTAssertTrue((tagged(app, "default_value_self").value as? String ?? tagged(app, "default_value_self").label).contains("-"))
            }
        }
        app.buttons["Back"].firstMatch.tap()
        app.buttons["Back"].firstMatch.tap()
        tap(app, "add_expense")
        verifySoftwareKeyboard(app, "expense_title")
        verifySoftwareKeyboard(app, "expense_amount", decimal: true)
        tap(app, "expense_currency")
        verifySoftwareKeyboard(app, "expense_currency_search")
        app.buttons["Cancel"].tap()
        tap(app, "expense_next")
        for mode in ["exact", "percent", "shares", "adjustment"] {
            tap(app, "split_mode_" + mode)
            XCTAssertTrue(tagged(app, "split_mode_" + mode).isSelected, "Split mode must actually change")
            verifySoftwareKeyboard(app, "split_self", decimal: true)
        }
        tap(app, "split_mode_equal")
        XCTAssertTrue(tagged(app, "split_mode_equal").isSelected)
        tap(app, "expense_next")
        tap(app, "expense_date_edit")
        verifySoftwareKeyboard(app, "expense_date")
        verifySoftwareKeyboard(app, "expense_notes")
        tap(app, "expense_previous")
        tap(app, "expense_previous")
        app.buttons["Back"].firstMatch.tap()
        tap(app, "discard_expense")
        tap(app, "group_action_settle")
        tap(app, "record_payment")
        verifySoftwareKeyboard(app, "settlement_amount", decimal: true)
        verifySoftwareKeyboard(app, "settlement_currency")
        verifySoftwareKeyboard(app, "settlement_date")
        app.buttons["Back"].firstMatch.tap()
        tap(app, "nav_friends")
        tap(app, "add_friend")
        verifySoftwareKeyboard(app, "friend_name")
        tap(app, "friend_save")
        tap(app, "nav_friends")
        app.buttons["Search friends"].tap()
        verifySoftwareKeyboard(app, "friend_search")
        // Close the focused search before reaching bottom navigation; the real
        // software keyboard intentionally covers those controls.
        app.buttons["Search friends"].tap()
        tap(app, "nav_groups")
        tap(app, "home_add_menu")
        tap(app, "global_add_expense")
        verifySoftwareKeyboard(app, "expense_destination_search")
    }

    // Uses real screen/status-bar/keyboard bounds, including physical iPhones.
    func testIsolatedSafeAreaAndKeyboard() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = "fairshare-ui-test-" + UUID().uuidString + ".db"
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 20), "Expected isolated storage; refusing writes. " + app.debugDescription)
        tap(app, "welcome_continue")
        XCTAssertTrue(tagged(app, "profile_name").waitForExistence(timeout: 10), app.debugDescription)
        let back = tagged(app, "profile_back")
        XCTAssertTrue(back.waitForExistence(timeout: 5))
        let statusBar = XCUIApplication(bundleIdentifier: "com.apple.springboard").statusBars.firstMatch
        XCTAssertTrue(statusBar.exists, "Need visible system chrome to check safe area")
        XCTAssertGreaterThanOrEqual(back.frame.minY, statusBar.frame.maxY,
            "Back must sit below the clock / Dynamic Island")
        let before = XCTAttachment(screenshot: app.screenshot())
        before.name = "Physical viewport — profile below status bar"
        before.lifetime = .keepAlways
        add(before)

        focusField(app, "profile_name")
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "Touching the name field must open the software keyboard. " + app.debugDescription)
        XCTAssertTrue(app.frame.intersects(app.keyboards.firstMatch.frame), "Software keyboard must be on screen, not merely present in accessibility data")
        // Tap visible software keys: typeText can inject text even with no keyboard.
        for character in "taylor" {
            let lower = app.keyboards.keys[String(character)].firstMatch
            let upper = app.keyboards.keys[String(character).uppercased()].firstMatch
            let key = lower.exists ? lower : upper
            XCTAssertTrue(key.waitForExistence(timeout: 3), "Missing visible software key: \(character)")
            XCTAssertTrue(app.frame.contains(CGPoint(x: key.frame.midX, y: key.frame.midY)), "Key must lie inside visible screen bounds")
            key.tap()
        }
        XCTAssertTrue((tagged(app, "profile_name").value as? String ?? "").lowercased().contains("taylor") || tagged(app, "profile_name").label.lowercased().contains("taylor"))
        let keyboard = app.keyboards.firstMatch
        let field = tagged(app, "profile_name")
        XCTAssertLessThanOrEqual(field.frame.maxY, keyboard.frame.minY + 1, "Focused editor must remain above keyboard")
        let typing = XCTAttachment(screenshot: app.screenshot())
        typing.name = "Physical viewport — typed name and software keyboard"
        typing.lifetime = .keepAlways
        add(typing)
        tap(app, "profile_continue")
        XCTAssertTrue(tagged(app, "nav_groups").waitForExistence(timeout: 10), app.debugDescription)
        tap(app, "nav_account")
        replace(app, "settings_name", "Viewport account")
        tap(app, "settings_save")
        XCTAssertTrue(tagged(app, "settings_name_saved").waitForExistence(timeout: 5))
        XCTAssertFalse(app.keyboards.firstMatch.exists, "Saving must dismiss keyboard")
        for mode in ["light", "dark", "system"] {
            tap(app, "theme_" + mode)
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = "Status bar and account — " + mode
            screenshot.lifetime = .keepAlways
            add(screenshot)
        }
    }

    func testIsolatedZeroitBrandingAndAppearance() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = "fairshare-ui-test-" + UUID().uuidString + ".db"
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 20))
        XCTAssertFalse(app.staticTexts["Fairshare"].exists)
        XCTAssertTrue(app.staticTexts["ZeroIT"].exists, app.debugDescription)
        let welcome = XCTAttachment(screenshot: app.screenshot())
        welcome.name = "ZeroIT onboarding"
        welcome.lifetime = .keepAlways
        add(welcome)
        completeOnboarding(app)
        tap(app, "nav_account")
        let about = app.staticTexts["ZeroIT · Local edition"].firstMatch
        for _ in 0..<5 {
            if about.exists && about.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(about.waitForExistence(timeout: 5), app.debugDescription)
        let capture = XCTAttachment(screenshot: app.screenshot())
        capture.name = "ZeroIT account branding"
        capture.lifetime = .keepAlways
        add(capture)
    }

    func testIOSLauncherIcon() {
        continueAfterFailure = false
        let app = XCUIApplication()
        launchFresh(app)
        XCUIDevice.shared.press(.home)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let icon = springboard.icons["ZeroIT"].firstMatch
        for _ in 0..<3 {
            if icon.exists && icon.isHittable { break }
            springboard.swipeLeft()
        }
        XCTAssertTrue(icon.waitForExistence(timeout: 5), springboard.debugDescription)
        let screenshot = XCTAttachment(screenshot: springboard.screenshot())
        screenshot.name = "ZeroIT installed launcher icon"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.activate()
    }

    func testIsolatedGroupsAddMenu() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = "fairshare-ui-test-" + UUID().uuidString + ".db"
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 15), "Expected isolated storage. " + app.debugDescription)
        completeOnboarding(app)
        tap(app, "nav_groups")
        XCTAssertFalse(tagged(app, "home_add_group").exists)
        XCTAssertFalse(tagged(app, "global_add_expense").exists)
        tap(app, "home_add_menu")
        XCTAssertTrue(tagged(app, "home_add_group").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(tagged(app, "global_add_expense").exists)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Groups quick add bubbles on iPhone"
        attachment.lifetime = .keepAlways
        add(attachment)
        tap(app, "home_add_menu")
        assertGone(app, "home_add_group")
        assertGone(app, "global_add_expense")

        tap(app, "home_add_menu")
        tap(app, "home_add_menu_dismiss")
        assertGone(app, "home_add_group")
        tap(app, "home_add_menu")
        tap(app, "home_add_group")
        XCTAssertTrue(tagged(app, "group_name").waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Back"].firstMatch.tap()
        tap(app, "home_add_menu")
        tap(app, "global_add_expense")
        XCTAssertTrue(tagged(app, "expense_destination_search").waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["New group"].firstMatch.tap()
        XCTAssertTrue(tagged(app, "group_name").waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Back"].firstMatch.tap()
        tap(app, "home_add_menu")
        tap(app, "nav_account")
        tap(app, "nav_groups")
        assertGone(app, "home_add_group")
    }

    private func assertGone(_ app: XCUIApplication, _ tag: String) {
        let absent = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: tagged(app, tag))
        XCTAssertEqual(XCTWaiter.wait(for: [absent], timeout: 5), .completed, app.debugDescription)
    }

    func testPersonalDemoLedgerReadOnly() {
        continueAfterFailure = false
        let app = XCUIApplication()
        launchFresh(app)
        tap(app, "nav_groups")
        app.buttons["Search groups"].tap()
        enter(app, "home_search", "Jamie")
        tap(app, "non_group_expenses")
        XCTAssertTrue(tagged(app, "group_expense_list").waitForExistence(timeout: 5), app.debugDescription)
        tap(app, "group_tab_balances")
        tap(app, "group_tab_totals")
        XCTAssertTrue(tagged(app, "group_total_USD").waitForExistence(timeout: 5), app.debugDescription)
        tap(app, "group_members")
        XCTAssertTrue(tagged(app, "members_title").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(tagged(app, "add_members").exists)
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(tagged(app, "group_tab_totals").isSelected, app.debugDescription)
    }

    func testExpenseWizardDraftCurrencyAndBackWithoutSaving() {
        continueAfterFailure = false
        let app = XCUIApplication()
        launchFresh(app)
        tap(app, "nav_groups")
        app.buttons["Search groups"].tap()
        enter(app, "home_search", "Weekend")
        tap(app, "group_optional-demo-weekend")
        tap(app, "add_expense")
        enter(app, "expense_title", "Unsaved travel demo")
        enter(app, "expense_amount", "30.00")
        tap(app, "expense_currency")
        enter(app, "expense_currency_search", "EUR")
        tap(app, "expense_currency_EUR")
        tap(app, "expense_next")
        tap(app, "expense_next")
        XCTAssertTrue(tagged(app, "expense_save").waitForExistence(timeout: 5), app.debugDescription)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "EUR expense review before saving"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.buttons["Back"].firstMatch.tap()
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(tagged(app, "expense_amount").waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Back"].firstMatch.tap()
        tap(app, "discard_expense")
        XCTAssertTrue(tagged(app, "group_title").waitForExistence(timeout: 5), app.debugDescription)
    }

    func testExpenseDestinationSearchWithoutSaving() {
        continueAfterFailure = false
        let app = XCUIApplication()
        launchFresh(app)
        tap(app, "nav_groups")
        tap(app, "home_add_menu")
        tap(app, "global_add_expense")
        enter(app, "expense_destination_search", "  wEeK  ")
        XCTAssertTrue(tagged(app, "choose_group_optional-demo-weekend").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(tagged(app, "choose_non_group").exists)
        tap(app, "expense_destination_clear")
        enter(app, "expense_destination_search", "no-such-destination-123")
        XCTAssertTrue(tagged(app, "expense_destination_empty").waitForExistence(timeout: 5), app.debugDescription)
        tap(app, "expense_destination_clear")
        enter(app, "expense_destination_search", "week")
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Search expense destinations with keyboard open"
        attachment.lifetime = .keepAlways
        add(attachment)
        tap(app, "choose_group_optional-demo-weekend")
        XCTAssertTrue(tagged(app, "expense_title").waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(tagged(app, "group_title").waitForExistence(timeout: 5), app.debugDescription)
    }

    func testGroupThemePersistsAcrossRelaunch() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let databaseName = "fairshare-ui-test-" + UUID().uuidString + ".db"
        let groupName = "iOS isolated theme"
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = databaseName
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 15), "Expected fresh isolated storage; refusing to edit the personal ledger. " + app.debugDescription)
        completeOnboarding(app)
        tap(app, "nav_groups")
        tap(app, "create_group")
        enter(app, "group_name", groupName)
        tap(app, "group_save")
        tap(app, "group_settings")
        XCTAssertTrue(tagged(app, "group_settings_screen").waitForExistence(timeout: 5), app.debugDescription)
        let themes = ["CLASSIC", "TRAVEL", "HOME", "DINING", "CELEBRATION", "NATURE"]
        guard let original = themes.first(where: { tagged(app, "group_theme_" + $0).isSelected }) else {
            XCTFail("No saved theme is selected: " + app.debugDescription)
            return
        }
        let target = original == "TRAVEL" ? "HOME" : "TRAVEL"
        tap(app, "group_theme_" + target)
        let targetSelected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "selected == true"), object: tagged(app, "group_theme_" + target))
        XCTAssertEqual(XCTWaiter.wait(for: [targetSelected], timeout: 5), .completed, app.debugDescription)
        app.terminate()
        launchFresh(app)
        tap(app, "nav_groups")
        let group = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@ AND identifier != %@", "group_", "group_title"))
            .firstMatch
        XCTAssertTrue(group.waitForExistence(timeout: 10), app.debugDescription)
        group.tap()
        tap(app, "group_settings")
        XCTAssertTrue(tagged(app, "group_theme_" + target).isSelected, app.debugDescription)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Saved group theme on iPhone 17"
        attachment.lifetime = .keepAlways
        add(attachment)
        tap(app, "group_theme_" + original)
        let restored = XCTNSPredicateExpectation(predicate: NSPredicate(format: "selected == true"), object: tagged(app, "group_theme_" + original))
        XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 5), .completed, app.debugDescription)
        tap(app, "group_settings_members")
        XCTAssertTrue(tagged(app, "members_title").waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(tagged(app, "group_settings_screen").waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(tagged(app, "group_title").waitForExistence(timeout: 5), app.debugDescription)
    }

    func testHomePullToRefreshReadOnly() {
        continueAfterFailure = false
        let app = XCUIApplication()
        launchFresh(app)
        tap(app, "nav_groups")
        let groups = tagged(app, "groups_screen")
        XCTAssertTrue(groups.waitForExistence(timeout: 10), app.debugDescription)
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let frame = groups.frame
        let start = origin.withOffset(CGVector(dx: frame.midX - app.frame.minX, dy: frame.minY + frame.height * 0.15 - app.frame.minY))
        let shortEnd = origin.withOffset(CGVector(dx: frame.midX - app.frame.minX, dy: frame.minY + frame.height * 0.43 - app.frame.minY))
        start.press(forDuration: 0.05, thenDragTo: shortEnd)
        XCTAssertFalse(app.staticTexts["Saved expenses and balances reloaded"].waitForExistence(timeout: 1), app.debugDescription)
        let finish = origin.withOffset(CGVector(dx: frame.midX - app.frame.minX, dy: frame.minY + frame.height * 0.97 - app.frame.minY))
        start.press(forDuration: 0.05, thenDragTo: finish)
        XCTAssertTrue(app.staticTexts["Saved expenses and balances reloaded"].waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(tagged(app, "nav_groups").isSelected, app.debugDescription)
    }

    func testLoadRequestedDemoScenarios() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = "fairshare-ui-test-" + UUID().uuidString + ".db"
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 15), "Expected fresh isolated storage; refusing to edit the personal ledger. " + app.debugDescription)
        completeOnboarding(app)
        tap(app, "nav_account")
        tap(app, "add_demo_scenarios")
        if !app.staticTexts["Demo · Maple House"].waitForExistence(timeout: 2) {
            // Compose's accessibility frame can lag the first post-scroll tap.
            // The loader is idempotent, so retrying this same action is safe.
            tap(app, "add_demo_scenarios")
        }
        XCTAssertTrue(app.staticTexts["Demo · Maple House"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.staticTexts["Demo · Lisbon Trip"].exists, app.debugDescription)
    }

    // Uses an existing local group; this smoke check never writes to the ledger.
    func testGroupWorkspaceReadOnlyNavigation() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        launchFresh(app)
        guard tagged(app, "nav_groups").waitForExistence(timeout: 15) else {
            throw XCTSkip("Complete onboarding and create a local group before this read-only smoke check.")
        }
        tap(app, "nav_groups")
        let group = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "group_")).matching(NSPredicate(format: "identifier != %@", "group_title")).firstMatch
        guard group.waitForExistence(timeout: 5) else { throw XCTSkip("A local group is required.") }
        group.tap()
        XCTAssertTrue(tagged(app, "group_balance").waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(tagged(app, "group_balance_currency_USD").exists, "The symbol already identifies USD.")
        XCTAssertFalse(tagged(app, "add_members").exists)
        tap(app, "group_tab_balances")
        tap(app, "group_members")
        XCTAssertTrue(tagged(app, "members_title").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "add_members").count, 1)
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(tagged(app, "group_tab_balances").isSelected, app.debugDescription)
        tap(app, "group_tab_expenses")
        let body = tagged(app, "group_expense_list")
        body.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).press(forDuration: 0.05, thenDragTo: body.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.92)))
        XCTAssertTrue(app.staticTexts["Saved group data reloaded"].waitForExistence(timeout: 5), app.debugDescription)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Compact group after refresh on iPhone"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func element(_ app: XCUIApplication, tag: String, label: String) -> XCUIElement {
        let tagged = app.descendants(matching: .any).matching(identifier: tag).firstMatch
        if tagged.exists { return tagged }
        let button = app.buttons.matching(NSPredicate(format: "label == %@", label)).firstMatch
        if button.exists { return button }
        return app.staticTexts.matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    private func completeOnboarding(_ app: XCUIApplication) {
        let start = element(app, tag: "welcome_continue", label: "Get started")
        if start.waitForExistence(timeout: 15) {
            tap(app, "welcome_continue")
            // Compose exposes its editable field as a native TextView on iOS.
            let name = app.descendants(matching: .any).matching(identifier: "profile_name").firstMatch
            XCTAssertTrue(name.waitForExistence(timeout: 5), app.debugDescription)
            enter(app, "profile_name", "Taylor")
            let next = element(app, tag: "profile_continue", label: "Let's get started")
            XCTAssertTrue(next.waitForExistence(timeout: 5), app.debugDescription)
            tap(app, "profile_continue")
        }
    }

    func testSharedOnboardingAndFourMobileTabs() {
        continueAfterFailure = false
        let app = XCUIApplication()
        launchFresh(app)
        completeOnboarding(app)
        for (tag, label) in [("nav_groups", "Groups"), ("nav_friends", "Friends"), ("nav_activity", "Activity"), ("nav_account", "Account")] {
            let tab = element(app, tag: tag, label: label)
            XCTAssertTrue(tab.waitForExistence(timeout: 10), app.debugDescription)
            tab.tap()
        }
        XCTAssertTrue(app.staticTexts["Appearance"].waitForExistence(timeout: 5), app.debugDescription)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Shared Account screen on iOS"
        attachment.lifetime = .keepAlways
        add(attachment)
        element(app, tag: "nav_groups", label: "Groups").tap()
    }

    // Force a new process; activating a user-opened app can retain its normal ledger.
    private func launchFresh(_ app: XCUIApplication) {
        if let database = app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] {
            app.launchArguments = ["--fairshare-ui-test-database", database]
        }
        app.terminate()
        app.launch()
    }

    private func tagged(_ app: XCUIApplication, _ tag: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: tag).firstMatch
    }

    private func tap(_ app: XCUIApplication, _ tag: String) {
        waitForAcknowledgementDismissal(app)
        let control = tagged(app, tag)
        XCTAssertTrue(control.exists || control.waitForExistence(timeout: 10), app.debugDescription)
        // Compose selectable columns can report isHittable=false while their
        // center is fully visible above the keyboard. Use a real screen touch;
        // callers assert the selected mode, so a missed touch cannot pass.
        if tag.hasPrefix("split_mode_") {
            for _ in 0..<8 {
                let frame = control.frame
                if interactionViewport(app, for: control).contains(CGPoint(x: frame.midX, y: frame.midY)) { break }
                scrollToward(control, in: app)
            }
            waitForStationaryFrame(control, in: app)
            control.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            return
        }
        // Group actions deliberately scroll horizontally after leftmost Settle up.
        if tag.hasPrefix("group_tab_") || tag == "group_action_settle" {
            for _ in 0..<3 {
                let frame = control.frame
                if frame.midX > app.frame.minX + 24 && frame.midX < app.frame.maxX - 24 { break }
                let origin = app.coordinate(withNormalizedOffset: .zero)
                let toLeft = frame.midX >= app.frame.maxX - 24
                // Offscreen Compose nodes can retain their old Y after safe-area
                // changes. Start the drag on a currently visible sibling chip.
                let visibleSibling = ["expenses", "balances", "totals", "activity"]
                    .map { tagged(app, "group_tab_" + $0) }
                    .first { $0.exists && $0.isHittable && app.frame.contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY)) }
                let y = (visibleSibling?.frame.midY ?? frame.midY) - app.frame.minY
                origin.withOffset(CGVector(dx: app.frame.width * (toLeft ? 0.85 : 0.15), dy: y)).press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: app.frame.width * (toLeft ? 0.2 : 0.8), dy: y)), withVelocity: .slow, thenHoldForDuration: 0.25)
            }
        }
        for _ in 0..<6 {
            if canTap(control, in: app) { break }
            scrollToward(control, in: app)
        }
        XCTAssertTrue(control.isHittable, app.debugDescription)
        waitForStationaryFrame(control, in: app)
        waitForAcknowledgementDismissal(app)
        control.tap()
    }

    // Destination content stays composed under the transaction acknowledgement.
    // Existence/hittability alone does not prove that a tap can reach that content.
    private func waitForAcknowledgementDismissal(_ app: XCUIApplication) {
        let overlay = tagged(app, "save_celebration")
        if !overlay.exists { return }
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: overlay)
        XCTAssertEqual(XCTWaiter.wait(for: [gone], timeout: 5), .completed,
            "Transaction acknowledgement still covers the next control. " + app.debugDescription)
    }

    // Compose scroll decay is not part of XCTest's native-idle detection.
    // Finish a slow drag with a held finger so the following tap cannot race a fling.
    private func canTap(_ control: XCUIElement, in app: XCUIApplication) -> Bool {
        guard control.isHittable else { return false }
        let frame = control.frame
        return interactionViewport(app, for: control).contains(CGPoint(x: frame.midX, y: frame.midY))
    }

    // A gesture below the software keyboard's top types/drags on the keyboard,
    // rather than scrolling the Compose page. Keep both endpoints in visible content.
    private func interactionViewport(_ app: XCUIApplication, for control: XCUIElement? = nil) -> CGRect {
        var viewport = app.frame.insetBy(dx: 0, dy: 34)
        let keyboard = app.keyboards.firstMatch
        if keyboard.exists && keyboard.frame.intersects(app.frame) {
            let bottom = min(viewport.maxY, keyboard.frame.minY - 34)
            viewport.size.height = max(0, bottom - viewport.minY)
        }
        if let control {
            let id = control.identifier
            let wizardBody = ["expense_title", "expense_amount", "expense_currency", "expense_date_edit", "expense_date", "expense_notes"].contains(id)
                || id.hasPrefix("split_") || id.hasPrefix("participant_") || id.hasPrefix("payer_")
            let hostTag = wizardBody ? "expense_step_content" : id.hasPrefix("default_") ? "group_defaults_screen" : ""
            if !hostTag.isEmpty {
                let host = tagged(app, hostTag)
                if host.exists { viewport = viewport.intersection(host.frame) }
            }
        }
        return viewport
    }

    private func scrollToward(_ control: XCUIElement, in app: XCUIApplication) {
        let viewport = interactionViewport(app, for: control)
        XCTAssertGreaterThan(viewport.height, 80, "No content area for scrolling. " + app.debugDescription)
        guard viewport.height > 80 else { return }
        let above = control.frame.midY < viewport.minY + viewport.height * 0.4
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let start = origin.withOffset(CGVector(dx: viewport.midX - app.frame.minX, dy: viewport.minY + viewport.height * (above ? 0.35 : 0.65) - app.frame.minY))
        let end = origin.withOffset(CGVector(dx: viewport.midX - app.frame.minX, dy: viewport.minY + viewport.height * (above ? 0.65 : 0.35) - app.frame.minY))
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.25)
    }

    private func waitForStationaryFrame(_ control: XCUIElement, in app: XCUIApplication) {
        let viewport = interactionViewport(app, for: control)
        let center = CGPoint(x: control.frame.midX, y: control.frame.midY)
        XCTAssertTrue(viewport.contains(center), "Control center is outside the visible safe area: " + control.debugDescription)
        var previous = control.frame
        var stableSamples = 0
        for _ in 0..<12 {
            Thread.sleep(forTimeInterval: 0.15)
            let current = control.frame
            if abs(current.minX - previous.minX) < 0.5 && abs(current.minY - previous.minY) < 0.5 {
                stableSamples += 1
                if stableSamples == 3 { return }
            } else {
                stableSamples = 0
            }
            previous = current
        }
        XCTFail("Control did not stop moving before tap: " + control.debugDescription)
    }

    private func enter(_ app: XCUIApplication, _ tag: String, _ text: String) {
        focusField(app, tag)
        app.typeText(text)
        // A keyboard already open for a previous field is not proof that this
        // editor received focus. Verify the intended field actually changed.
        let entered = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", text, text),
            object: tagged(app, tag))
        XCTAssertEqual(XCTWaiter.wait(for: [entered], timeout: 5), .completed,
            "Text did not reach the requested editor " + tag + ": " + app.debugDescription)
    }

    private func focusField(_ app: XCUIApplication, _ tag: String) {
        waitForAcknowledgementDismissal(app)
        let control = tagged(app, tag)
        // Compose's native accessibility TextView can acknowledge an accessibility
        // tap without focusing its editor. Bring the field into the visible
        // content area before opening the keyboard, then tap its interior once.
        // Tapping first lets the keyboard cover the field and makes later scroll
        // gestures land on keyboard keys instead of the Compose form.
        for _ in 0..<12 {
            if canTap(control, in: app) { break }
            // Keep gestures inside the content viewport above the keyboard.
            // A guessed dismissal coordinate can toggle a real form control.
            scrollToward(control, in: app)
            Thread.sleep(forTimeInterval: 0.2)
        }
        XCTAssertTrue(canTap(control, in: app), "Editor must be visible before focus: " + control.debugDescription)
        waitForStationaryFrame(control, in: app)
        // Tap well inside the field. A lower-edge tap can land on the home-indicator
        // area for a 52 pt field positioned at the bottom of the iPhone viewport.
        control.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35)).tap()
        // Confirm focus by typing and checking the intended field in `enter`.
        // Simulator keyboard windows are inconsistently exposed to XCTest.
    }

    // Each run has fresh storage. Keep the same name through relaunch to verify persistence.
    // This test never opens the installed user's fairshare.db.
    func testIsolatedPhaseOneLedgerLifecycle() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let databaseName = "fairshare-ui-test-" + UUID().uuidString + ".db"
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = databaseName
        print("ISOLATED_ACCEPTANCE_DATABASE=" + databaseName)
        launchFresh(app)
        // Refuse all writes if the Debug isolation gate was omitted from the build.
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 15), "Expected fresh isolated storage; refusing ledger writes. " + app.debugDescription)
        completeOnboarding(app)
        tap(app, "nav_groups")
        tap(app, "create_group")
        let groupName = "iOS isolated acceptance"
        enter(app, "group_name", groupName)
        tap(app, "group_save")
        tap(app, "group_members")
        tap(app, "add_members")
        for person in ["Alice", "Bob"] {
            tap(app, "member_new")
            enter(app, "member_new_name", person)
            tap(app, "member_new_add")
        }
        tap(app, "member_next")
        tap(app, "member_confirm")
        XCTAssertTrue(tagged(app, "members_title").waitForExistence(timeout: 10), app.debugDescription)
        app.buttons["Back"].firstMatch.tap()

        tap(app, "add_expense")
        // Invalid input must keep the draft on Details rather than saving anything.
        tap(app, "expense_next")
        XCTAssertTrue(tagged(app, "expense_error").waitForExistence(timeout: 5), app.debugDescription)
        enter(app, "expense_title", "Acceptance dinner")
        enter(app, "expense_amount", "30.00")
        tap(app, "expense_currency")
        enter(app, "expense_currency_search", "USD")
        tap(app, "expense_currency_USD")
        tap(app, "expense_next")
        tap(app, "expense_next")
        tap(app, "expense_save")
        XCTAssertTrue(tagged(app, "group_title").waitForExistence(timeout: 10), app.debugDescription)
        tapText(app, "Acceptance dinner")
        XCTAssertTrue(app.staticTexts["Who owes what"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", "$10.00")).count, 3, app.debugDescription)
        app.buttons["Back"].firstMatch.tap()
        assertBalance(app, "$20.00")

        // A partial payment reduces what the profile receives from $20 to $17.
        tap(app, "group_action_settle")
        tap(app, "settle_up")
        replace(app, "settlement_amount", "3.00")
        tap(app, "settlement_save")
        assertBalance(app, "$17.00")
        tap(app, "group_tab_expenses")
        tapText(app, "Acceptance dinner")
        tap(app, "expense_edit")
        replace(app, "expense_amount", "36.00")
        tap(app, "expense_next")
        tap(app, "split_mode_equal")
        tap(app, "expense_next")
        tap(app, "expense_save")
        XCTAssertTrue(app.staticTexts["Who owes what"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", "$12.00")).count, 3, app.debugDescription)
        tap(app, "expense_delete")
        tap(app, "confirm_delete")
        XCTAssertTrue(tagged(app, "expense_restore").waitForExistence(timeout: 10), app.debugDescription)
        tap(app, "expense_restore")
        XCTAssertTrue(tagged(app, "expense_edit").waitForExistence(timeout: 10), app.debugDescription)
        app.buttons["Back"].firstMatch.tap()
        assertBalance(app, "$21.00")

        // A second currency belongs to this same group and remains a separate balance.
        tap(app, "add_expense")
        enter(app, "expense_title", "Acceptance EUR taxi")
        enter(app, "expense_amount", "9.00")
        tap(app, "expense_currency")
        enter(app, "expense_currency_search", "EUR")
        tap(app, "expense_currency_EUR")
        tap(app, "expense_next")
        tap(app, "expense_next")
        tap(app, "expense_save")
        XCTAssertTrue(tagged(app, "group_title").waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(tagged(app, "expense_edit").exists, app.debugDescription)
        XCTAssertTrue(tagged(app, "group_balance_EUR").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(tagged(app, "group_balance_USD").exists, app.debugDescription)
        tap(app, "group_settings")
        tap(app, "group_theme_TRAVEL")
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "selected == true"), object: tagged(app, "group_theme_TRAVEL"))
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 5), .completed, app.debugDescription)

        app.terminate()
        launchFresh(app)
        tap(app, "nav_groups")
        tapText(app, groupName)
        XCTAssertTrue(app.staticTexts["Acceptance dinner"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.staticTexts["Acceptance EUR taxi"].exists, app.debugDescription)
        XCTAssertTrue(tagged(app, "group_balance_USD").exists, app.debugDescription)
        XCTAssertTrue(tagged(app, "group_balance_EUR").exists, app.debugDescription)
        tap(app, "group_action_settle")
        tap(app, "group_currency_USD")
        tap(app, "group_recorded_payments")
        assertBalance(app, "$21.00")
        XCTAssertTrue(app.staticTexts["$3.00"].exists, app.debugDescription)
        let paymentDelete = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "delete_settlement_")).firstMatch
        XCTAssertTrue(paymentDelete.waitForExistence(timeout: 5), app.debugDescription)
        let paymentId = String(paymentDelete.identifier.dropFirst("delete_settlement_".count))
        for _ in 0..<6 {
            if canTap(paymentDelete, in: app) { break }
            scrollToward(paymentDelete, in: app)
        }
        waitForStationaryFrame(paymentDelete, in: app)
        paymentDelete.tap()
        tap(app, "confirm_delete_settlement")
        assertBalance(app, "$24.00")
        app.terminate()
        launchFresh(app)
        tap(app, "nav_groups")
        tapText(app, groupName)
        assertBalance(app, "$24.00")
        XCTAssertFalse(tagged(app, "group_payment_" + paymentId).exists, app.debugDescription)
        tap(app, "group_action_settle")
        tap(app, "group_currency_USD")
        XCTAssertFalse(tagged(app, "group_recorded_payments").exists, app.debugDescription)
        XCTAssertFalse(tagged(app, "delete_settlement_" + paymentId).exists, app.debugDescription)
        tap(app, "group_settings")
        XCTAssertTrue(tagged(app, "group_theme_TRAVEL").isSelected, app.debugDescription)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Isolated Phase 1 settings persisted on iPhone 17"
        attachment.lifetime = .keepAlways
        add(attachment)
        print("ISOLATED_ACCEPTANCE_DATABASE=" + databaseName)
    }

    func testIsolatedGroupSettingsAndRecoverableLifecycle() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let databaseName = "fairshare-ui-test-" + UUID().uuidString + ".db"
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = databaseName
        print("ISOLATED_SETTINGS_DATABASE=" + databaseName)
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 15), "Expected fresh isolated storage; refusing writes. " + app.debugDescription)
        completeOnboarding(app)
        tap(app, "nav_groups")
        tap(app, "create_group")
        let groupName = "iOS isolated settings"
        enter(app, "group_name", groupName)
        tap(app, "group_save")
        tap(app, "group_settings")
        tap(app, "group_simplify_debts")
        tap(app, "group_simplify_confirm")
        XCTAssertTrue(app.staticTexts["Keep the original payer-to-participant debts, netting payments only between the same two people."].waitForExistence(timeout: 5), app.debugDescription)
        tap(app, "group_simplify_debts")
        tap(app, "group_simplify_confirm")
        XCTAssertTrue(app.staticTexts["Combine debts within each currency to suggest fewer repayments. Everyone's net balance stays the same."].waitForExistence(timeout: 5), app.debugDescription)
        tap(app, "group_settings_members")
        tap(app, "add_members")
        tap(app, "member_new")
        enter(app, "member_new_name", "Alice")
        tap(app, "member_new_add")
        tap(app, "member_next")
        tap(app, "member_confirm")
        XCTAssertTrue(tagged(app, "members_title").waitForExistence(timeout: 5), app.debugDescription)
        let alice = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@ AND identifier != %@", "member_row_", "member_row_self")).firstMatch
        XCTAssertTrue(alice.waitForExistence(timeout: 5), app.debugDescription)
        let aliceId = String(alice.identifier.dropFirst("member_row_".count))
        let balance = tagged(app, "member_balance_" + aliceId + "_USD")
        XCTAssertTrue(balance.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertEqual(balance.label, "USD · settled $0.00", app.debugDescription)
        tap(app, "remove_member_" + aliceId)
        tap(app, "member_remove_confirm")
        let removed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: tagged(app, "member_row_" + aliceId))
        XCTAssertEqual(XCTWaiter.wait(for: [removed], timeout: 5), .completed, app.debugDescription)
        tap(app, "add_members")
        tap(app, "member_select_" + aliceId)
        tap(app, "member_next")
        tap(app, "member_confirm")
        XCTAssertTrue(tagged(app, "member_row_" + aliceId).waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Back"].firstMatch.tap()
        tap(app, "group_defaults")
        XCTAssertTrue(tagged(app, "group_defaults_screen").waitForExistence(timeout: 5), app.debugDescription)
        tap(app, "default_participant_" + aliceId)
        tap(app, "default_payer_self")
        tap(app, "default_mode_SHARES")
        enter(app, "default_value_self", "1")
        tap(app, "defaults_save")
        XCTAssertTrue(tagged(app, "group_defaults_summary").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(app.staticTexts["Taylor pays · shares"].exists, app.debugDescription)
        app.buttons["Back"].firstMatch.tap()
        tap(app, "add_expense")
        XCTAssertTrue(tagged(app, "expense_defaults_hint").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(tagged(app, "expense_currency").label.contains("USD"), app.debugDescription)
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(tagged(app, "group_title").waitForExistence(timeout: 5), app.debugDescription)

        tap(app, "group_settings")
        tap(app, "group_leave")
        tap(app, "group_visibility_confirm")
        XCTAssertTrue(tagged(app, "groups_screen").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(app.staticTexts[groupName].exists, app.debugDescription)
        restoreOnlyHiddenGroup(app)
        tap(app, "nav_groups")
        tapText(app, groupName)
        tap(app, "group_settings")
        tap(app, "group_delete")
        tap(app, "group_visibility_confirm")
        XCTAssertTrue(tagged(app, "groups_screen").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(app.staticTexts[groupName].exists, app.debugDescription)
        restoreOnlyHiddenGroup(app)
        app.terminate()
        launchFresh(app)
        tap(app, "nav_groups")
        tapText(app, groupName)
        tap(app, "group_settings")
        tap(app, "group_defaults")
        XCTAssertTrue(tagged(app, "default_mode_SHARES").isSelected, app.debugDescription)
        XCTAssertEqual((tagged(app, "default_value_self").value as? String).flatMap { $0.isEmpty ? nil : $0 } ?? tagged(app, "default_value_self").label, "1", app.debugDescription)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Personal defaults survived leave delete restore and relaunch"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testIsolatedSplitModesAndMultiplePayers() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let databaseName = "fairshare-ui-test-" + UUID().uuidString + ".db"
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = databaseName
        print("ISOLATED_SPLITS_DATABASE=" + databaseName)
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 15), "Expected fresh isolated storage; refusing writes. " + app.debugDescription)
        completeOnboarding(app)
        tap(app, "nav_groups")
        tap(app, "create_group")
        enter(app, "group_name", "iOS split methods")
        tap(app, "group_save")
        tap(app, "group_members")
        tap(app, "add_members")
        tap(app, "member_new")
        enter(app, "member_new_name", "Alice")
        tap(app, "member_new_add")
        tap(app, "member_next")
        tap(app, "member_confirm")
        XCTAssertTrue(tagged(app, "members_title").waitForExistence(timeout: 5), app.debugDescription)
        let alice = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@ AND identifier != %@", "member_row_", "member_row_self")).firstMatch
        XCTAssertTrue(alice.waitForExistence(timeout: 5), app.debugDescription)
        let aliceId = String(alice.identifier.dropFirst("member_row_".count))
        XCTAssertFalse(aliceId.isEmpty)
        app.buttons["Back"].firstMatch.tap()

        for (mode, own, other) in [("exact", "5.00", "5.00"), ("percent", "50", "50"), ("shares", "1", "1"), ("adjustment", "-1", "1")] {
            beginUSDExpense(app, title: "Acceptance " + mode)
            tap(app, "expense_next")
            tap(app, "split_mode_" + mode)
            enter(app, "split_self", own)
            enter(app, "split_" + aliceId, other)
            tap(app, "expense_next")
            tap(app, "expense_save")
            XCTAssertTrue(tagged(app, "group_title").waitForExistence(timeout: 10), app.debugDescription)
            tapText(app, "Acceptance " + mode)
            XCTAssertTrue(app.staticTexts["Who owes what"].waitForExistence(timeout: 10), app.debugDescription)
            if mode == "adjustment" {
                XCTAssertTrue(app.staticTexts["$4.00"].exists, app.debugDescription)
                XCTAssertTrue(app.staticTexts["$6.00"].exists, app.debugDescription)
            } else {
                XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", "$5.00")).count, 2, app.debugDescription)
            }
            app.buttons["Back"].firstMatch.tap()
        }
        // Four expenses give self 5+5+5+6 = 21; the adjustment leaves Alice owing 6.
        assertBalance(app, "$21.00")
        beginUSDExpense(app, title: "Acceptance multiple payers")
        tap(app, "payer_" + aliceId)
        enter(app, "payer_amount_self", "6.00")
        enter(app, "payer_amount_" + aliceId, "4.00")
        tap(app, "expense_next")
        tap(app, "expense_next")
        tap(app, "expense_save")
        XCTAssertTrue(tagged(app, "group_title").waitForExistence(timeout: 10), app.debugDescription)
        tapText(app, "Acceptance multiple payers")
        XCTAssertTrue(app.staticTexts["Who owes what"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", "$5.00")).count, 2, app.debugDescription)
        XCTAssertTrue(app.staticTexts["$6.00"].exists, app.debugDescription)
        XCTAssertTrue(app.staticTexts["$4.00"].exists, app.debugDescription)
        app.buttons["Back"].firstMatch.tap()
        assertBalance(app, "$22.00")
        app.terminate()
        launchFresh(app)
        tap(app, "nav_groups")
        tapText(app, "iOS split methods")
        assertBalance(app, "$22.00")
        XCTAssertTrue(app.staticTexts["Acceptance multiple payers"].exists, app.debugDescription)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "All split modes and multiple payers persisted on iPhone 17"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testIsolatedCompactExpenseAndNameFeedback() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let databaseName = "fairshare-ui-test-" + UUID().uuidString + ".db"
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = databaseName
        print("ISOLATED_COMPACT_EXPENSE_DATABASE=" + databaseName)
        launchFresh(app)
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 15), "Expected isolated storage; refusing writes. " + app.debugDescription)
        completeOnboarding(app)
        tap(app, "nav_account")
        replace(app, "settings_name", "TIRTH Shah")
        tap(app, "settings_save")
        XCTAssertTrue(tagged(app, "settings_name_saved").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(app.keyboards.firstMatch.exists, "Saving the name should dismiss the keyboard. " + app.debugDescription)
        XCTAssertFalse(tagged(app, "settings_save").isEnabled, app.debugDescription)

        tap(app, "nav_groups")
        tap(app, "create_group")
        enter(app, "group_name", "Compact expense acceptance")
        tap(app, "group_save")
        tap(app, "group_members")
        tap(app, "add_members")
        tap(app, "member_new")
        enter(app, "member_new_name", "Alice")
        tap(app, "member_new_add")
        tap(app, "member_next")
        tap(app, "member_confirm")
        let alice = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@ AND identifier != %@", "member_row_", "member_row_self")).firstMatch
        XCTAssertTrue(alice.waitForExistence(timeout: 5), app.debugDescription)
        let aliceId = String(alice.identifier.dropFirst("member_row_".count))
        XCTAssertFalse(aliceId.isEmpty)
        app.buttons["Back"].firstMatch.tap()
        tap(app, "add_expense")
        enter(app, "expense_title", "Lunch with Alice")
        XCTAssertFalse(tagged(app, "participant_self").exists, "Step one should select payers only.")
        enter(app, "expense_amount", "23.23")
        tap(app, "expense_currency")
        XCTAssertTrue(tagged(app, "expense_currency_more").waitForExistence(timeout: 5), app.debugDescription)
        enter(app, "expense_currency_search", "USD")
        tap(app, "expense_currency_USD")
        tap(app, "payer_" + aliceId)
        enter(app, "payer_amount_self", "10")
        let remainder = tagged(app, "payer_remaining_" + aliceId)
        XCTAssertTrue(remainder.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(remainder.label.contains("13.23"), app.debugDescription)
        tap(app, "expense_next")
        XCTAssertTrue(tagged(app, "expense_error").waitForExistence(timeout: 5), app.debugDescription)
        tap(app, "payer_remaining_" + aliceId)
        tap(app, "expense_next")
        let equalMode = tagged(app, "split_mode_equal")
        XCTAssertTrue(equalMode.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(tagged(app, "participant_self").exists, "Participant selection belongs in Split.")
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "selected == true"), object: equalMode)
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 5), .completed, app.debugDescription)
        let splitCapture = XCTAttachment(screenshot: app.screenshot())
        splitCapture.name = "Compact equal split step"
        splitCapture.lifetime = .keepAlways
        add(splitCapture)
        tap(app, "expense_next")
        XCTAssertTrue(tagged(app, "expense_notes").waitForExistence(timeout: 5), app.debugDescription)
        let reviewCapture = XCTAttachment(screenshot: app.screenshot())
        reviewCapture.name = "Compact review and multiline notes"
        reviewCapture.lifetime = .keepAlways
        add(reviewCapture)
        enter(app, "expense_notes", "Lunch and shared travel plans.\nKeep the receipt for later.")
        tap(app, "expense_save")
        let expenseConfirmation = tagged(app, "save_celebration")
        if expenseConfirmation.exists {
            let quickDismissal = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: expenseConfirmation)
            XCTAssertEqual(XCTWaiter.wait(for: [quickDismissal], timeout: 1.2), .completed,
                "Saved expense confirmation must release the feed promptly")
        }
        XCTAssertTrue(tagged(app, "group_title").waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(tagged(app, "expense_edit").exists, "New expenses should return to the feed. " + app.debugDescription)
        XCTAssertTrue(app.staticTexts["Lunch with Alice"].waitForExistence(timeout: 5), app.debugDescription)
        let feedCapture = XCTAttachment(screenshot: app.screenshot())
        feedCapture.name = "Expense feed — accent Add expense above neutral cards"
        feedCapture.lifetime = .keepAlways
        add(feedCapture)
        tapText(app, "Lunch with Alice")
        XCTAssertTrue(app.staticTexts["Who owes what"].waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(app.staticTexts["$10.00"].waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(app.staticTexts["$13.23"].waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(app.staticTexts["Lunch and shared travel plans.\nKeep the receipt for later."].waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Back"].firstMatch.tap()
        tap(app, "group_tab_totals")
        XCTAssertTrue(tagged(app, "group_monthly_chart_USD").waitForExistence(timeout: 5), app.debugDescription)
        tap(app, "chart_members_USD")
        XCTAssertTrue(tagged(app, "chart_member_self_USD").waitForExistence(timeout: 5), app.debugDescription)
        tap(app, "chart_monthly_USD")
        let reportCapture = XCTAttachment(screenshot: app.screenshot())
        reportCapture.name = "Compact totals with spending and personal share"
        reportCapture.lifetime = .keepAlways
        add(reportCapture)
        tap(app, "group_action_settle")
        tap(app, "settle_up")
        XCTAssertTrue(tagged(app, "settlement_people_summary").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(tagged(app, "settlement_from_self").exists, "Known settlement should not repeat person choices.")
        let settlementCapture = XCTAttachment(screenshot: app.screenshot())
        settlementCapture.name = "Compact contextual settlement"
        settlementCapture.lifetime = .keepAlways
        add(settlementCapture)
        tap(app, "settlement_save")
        let paymentConfirmation = tagged(app, "save_celebration")
        if paymentConfirmation.exists {
            paymentConfirmation.tap()
            XCTAssertFalse(paymentConfirmation.exists, "Tapping a confirmation must dismiss it immediately")
        }
        XCTAssertTrue(app.staticTexts["Payment recorded"].firstMatch.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(tagged(app, "group_title").waitForExistence(timeout: 10), app.debugDescription)
        tap(app, "nav_activity")
        let paymentMessage = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Recorded external payment:")).firstMatch
        XCTAssertTrue(paymentMessage.waitForExistence(timeout: 5), app.debugDescription)
        paymentMessage.swipeRight()
        XCTAssertTrue(tagged(app, "settlement_amount").waitForExistence(timeout: 5), app.debugDescription)
        replace(app, "settlement_amount", "1.00")
        tap(app, "settlement_save")
        XCTAssertTrue(app.staticTexts["Payment updated"].firstMatch.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(tagged(app, "activity_screen").waitForExistence(timeout: 10), app.debugDescription)
    }

    func testIsolatedCurrencyDefaultsBalanceToolsAndSixMonthChart() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["FAIRSHARE_UI_TEST_DATABASE"] = "fairshare-ui-test-" + UUID().uuidString + ".db"
        launchFresh(app)
        // Refuse fixture writes if this launch ever opens the installed personal ledger.
        XCTAssertTrue(tagged(app, "welcome_continue").waitForExistence(timeout: 15), "Expected isolated storage. " + app.debugDescription)
        completeOnboarding(app)
        tap(app, "nav_account")
        tap(app, "add_demo_scenarios")
        tap(app, "group_demo-scenarios-monthly-v1")
        let dollars = tagged(app, "group_balance_USD")
        let euros = tagged(app, "group_balance_EUR")
        XCTAssertTrue(dollars.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(euros.exists, app.debugDescription)
        XCTAssertEqual(dollars.frame.minY, euros.frame.minY, accuracy: 2)
        XCTAssertEqual(dollars.frame.height, euros.frame.height, accuracy: 2)
        XCTAssertLessThanOrEqual(dollars.frame.maxX, euros.frame.minX)
        XCTAssertFalse(tagged(app, "group_balance_currency_USD").exists)
        XCTAssertFalse(tagged(app, "group_balance_currency_EUR").exists)

        tap(app, "add_expense")
        let currency = tagged(app, "expense_currency")
        let amount = tagged(app, "expense_amount")
        XCTAssertTrue(currency.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(currency.label.contains("USD"), "New drafts must use this group's default. " + app.debugDescription)
        XCTAssertTrue(amount.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertLessThan(currency.frame.maxX, amount.frame.minX)
        XCTAssertLessThan(currency.frame.width, amount.frame.width / 2)
        XCTAssertEqual(currency.frame.maxY, amount.frame.maxY, accuracy: 3)
        XCTAssertGreaterThanOrEqual(currency.frame.height, 48)
        tap(app, "expense_currency")
        enter(app, "expense_currency_search", "EUR")
        tap(app, "expense_currency_EUR")
        XCTAssertTrue(currency.label.contains("EUR"), app.debugDescription)
        let currencyCapture = XCTAttachment(screenshot: app.screenshot())
        currencyCapture.name = "Rectangular currency left of amount with editable group default"
        currencyCapture.lifetime = .keepAlways
        add(currencyCapture)
        app.buttons["Back"].firstMatch.tap()
        tap(app, "discard_expense")

        tap(app, "group_tab_totals")
        tap(app, "chart_stacked_USD")
        assertSelected(app, "chart_stacked_USD")
        let monthQuery = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@ AND identifier ENDSWITH %@", "chart_month_", "_USD"))
        XCTAssertEqual(monthQuery.count, 6, "Sample chart should cover six distinct calendar months. " + app.debugDescription)
        let months = monthQuery.allElementsBoundByIndex
        XCTAssertTrue(months.first?.label.contains("your share") == true, app.debugDescription)
        if let first = months.first, let last = months.last {
            tap(app, first.identifier)
            let firstSelected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isSelected == true"), object: tagged(app, first.identifier))
            XCTAssertEqual(XCTWaiter.wait(for: [firstSelected], timeout: 5), .completed, app.debugDescription)
            tap(app, "chart_total_USD")
            assertSelected(app, "chart_total_USD")
            tap(app, last.identifier)
            let lastSelected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isSelected == true"), object: tagged(app, last.identifier))
            XCTAssertEqual(XCTWaiter.wait(for: [lastSelected], timeout: 5), .completed, app.debugDescription)
            tap(app, "chart_stacked_USD")
        }
        let chartCapture = XCTAttachment(screenshot: app.screenshot())
        chartCapture.name = "Six-month vertical stacked spending comparison on iPhone"
        chartCapture.lifetime = .keepAlways
        add(chartCapture)
        app.buttons["Back"].firstMatch.tap()
        // Group Back returns from Totals to Expenses before leaving the group.
        if tagged(app, "group_title").exists { app.buttons["Back"].firstMatch.tap() }

        tap(app, "home_balance_tools")
        tap(app, "balance_filter_OWE")
        tap(app, "balance_sort_LARGEST")
        tap(app, "balance_sort_currency_USD")
        assertSelected(app, "balance_filter_OWE")
        assertSelected(app, "balance_sort_LARGEST")
        tap(app, "balance_tools_done")
        XCTAssertTrue(tagged(app, "home_balance_summary").waitForExistence(timeout: 5), app.debugDescription)
        tap(app, "nav_friends")
        tap(app, "home_balance_tools")
        XCTAssertTrue(tagged(app, "balance_filter_ALL").isSelected, "Friends should keep an independent filter. " + app.debugDescription)
        tap(app, "balance_filter_OWED")
        tap(app, "balance_tools_done")
        tap(app, "nav_groups")
        tap(app, "home_balance_tools")
        XCTAssertTrue(tagged(app, "balance_filter_OWE").isSelected, "Group filter should survive tab changes. " + app.debugDescription)
        tap(app, "balance_tools_reset")
        assertSelected(app, "balance_filter_ALL")
        tap(app, "balance_tools_done")
    }

    private func beginUSDExpense(_ app: XCUIApplication, title: String) {
        tap(app, "add_expense")
        enter(app, "expense_title", title)
        enter(app, "expense_amount", "10.00")
        tap(app, "expense_currency")
        enter(app, "expense_currency_search", "USD")
        tap(app, "expense_currency_USD")
    }

    private func restoreOnlyHiddenGroup(_ app: XCUIApplication) {
        tap(app, "nav_account")
        let restore = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "restore_group_")).firstMatch
        XCTAssertTrue(restore.waitForExistence(timeout: 5), app.debugDescription)
        for _ in 0..<6 {
            if canTap(restore, in: app) { break }
            scrollToward(restore, in: app)
        }
        XCTAssertTrue(restore.isHittable, app.debugDescription)
        waitForStationaryFrame(restore, in: app)
        restore.tap()
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: restore)
        XCTAssertEqual(XCTWaiter.wait(for: [gone], timeout: 5), .completed, app.debugDescription)
    }

    private func tapText(_ app: XCUIApplication, _ text: String) {
        waitForAcknowledgementDismissal(app)
        let control = app.staticTexts[text].firstMatch
        XCTAssertTrue(control.waitForExistence(timeout: 10), app.debugDescription)
        for _ in 0..<6 {
            if canTap(control, in: app) { break }
            scrollToward(control, in: app)
        }
        XCTAssertTrue(control.isHittable, app.debugDescription)
        waitForStationaryFrame(control, in: app)
        waitForAcknowledgementDismissal(app)
        control.tap()
    }

    private func replace(_ app: XCUIApplication, _ tag: String, _ text: String) {
        focusField(app, tag)
        let control = tagged(app, tag)
        let old = (control.value as? String).flatMap { $0.isEmpty ? nil : $0 } ?? control.label
        XCTAssertFalse(old.isEmpty, "Expected existing editable value: " + app.debugDescription)
        // Place the caret at the trailing edge before deleting. A centered tap can
        // leave it at the start on iOS, causing the replacement to prepend text.
        control.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        app.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old.count) + text)
        let replaced = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@ OR value == %@", text, text), object: tagged(app, tag))
        XCTAssertEqual(XCTWaiter.wait(for: [replaced], timeout: 5), .completed,
            "Replacement did not reach the requested editor " + tag + ": " + app.debugDescription)
    }

    private func assertSelected(_ app: XCUIApplication, _ tag: String) {
        // Compose's semantic update may follow XCTest's native idle signal.
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isSelected == true"), object: tagged(app, tag))
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 5), .completed, app.debugDescription)
    }

    private func assertBalance(_ app: XCUIApplication, _ expected: String) {
        let balance = tagged(app, "group_balance")
        XCTAssertTrue(balance.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(balance.label.hasSuffix(" " + expected + " USD"), app.debugDescription)
    }
}
