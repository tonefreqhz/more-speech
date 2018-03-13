/*
 * Copyright 2018 Chris Kelly
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
package com.tolstoy.censorship.twitter.checker.app;

import java.util.*;
import java.sql.*;
import javax.swing.*;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.tolstoy.basic.api.storage.*;
import com.tolstoy.basic.api.tweet.*;
import com.tolstoy.basic.api.utils.*;
import com.tolstoy.basic.api.statusmessage.*;
import com.tolstoy.basic.app.utils.Utils;
import com.tolstoy.censorship.twitter.checker.api.preferences.*;
import com.tolstoy.censorship.twitter.checker.api.webdriver.*;
import com.tolstoy.censorship.twitter.checker.api.snapshot.*;
import com.tolstoy.censorship.twitter.checker.api.searchrun.*;
import com.tolstoy.censorship.twitter.checker.app.gui.*;
import com.tolstoy.censorship.twitter.checker.app.helpers.SearchRunBuilder;
import com.tolstoy.basic.gui.ElementDescriptor;
import com.seaglasslookandfeel.*;

public class AppGUI implements RunEventListener, PreferencesEventListener, WindowClosingEventListener {
	private static final Logger logger = LogManager.getLogger( AppGUI.class );

	private IResourceBundleWithFormatting bundle;
	private IStorage storage;
	private IPreferencesFactory prefsFactory;
	private IPreferences prefs;
	private IWebDriverFactory webDriverFactory;
	private ISearchRunFactory searchRunFactory;
	private ISnapshotFactory snapshotFactory;
	private ITweetFactory tweetFactory;
	private List<ISearchRunRepliesProcessor> searchRunProcessors;
	private List<ElementDescriptor> guiElements;
	private MainGUI gui;

	class Worker extends SwingWorker<ISearchRunReplies, StatusMessage> implements IStatusMessageReceiver {
		@Override
		protected void process( List<StatusMessage> messages ) {
			for ( StatusMessage message : messages ) {
				if ( message == null ) {
					gui.clearMessages();
				}
				else {
					gui.addMessage( message );
				}
			}
		}

		@Override
		public void addMessage( StatusMessage message ) {
			publish( message );
		}

		@Override
		public void clearMessages() {
			publish( null );
		}

		@Override
		public ISearchRunReplies doInBackground() {
			try {
				SearchRunBuilder builder = new SearchRunBuilder( bundle,
												storage,
												prefsFactory,
												prefs,
												webDriverFactory,
												searchRunFactory,
												snapshotFactory,
												tweetFactory,
												this,
												prefs.getValue( "prefs.handle_to_check" ) );

				int numTimelinePagesToCheck = Utils.parseIntDefault( prefs.getValue( "prefs.num_timeline_pages_to_check" ), 1 );
				int numIndividualPagesToCheck = Utils.parseIntDefault( prefs.getValue( "prefs.num_individual_pages_to_check" ), 3 );
				int maxTweets = Utils.parseIntDefault( prefs.getValue( "prefs.num_tweets_to_check" ), 5 );

				ISearchRunReplies searchRunReplies = builder.buildSearchRunReplies( numTimelinePagesToCheck,
																					numIndividualPagesToCheck,
																					maxTweets );

				//logger.info( searchRunReplies );
				logger.info( "VALUENEXT" );
				logger.info( Utils.getDefaultObjectMapper().writeValueAsString( searchRunReplies ) );
				return searchRunReplies;
			}
			catch ( Exception e ) {
				logger.error( bundle.getString( "exc_start", e.getMessage() ), e );
				publish( new StatusMessage( bundle.getString( "exc_start", e.getMessage() ), StatusMessageSeverity.ERROR ) );

				return null;
			}
		}

		@Override
		public void done() {
			try {
				ISearchRunReplies searchRunReplies = get();
				//logger.info( searchRunReplies );

				for ( ISearchRunRepliesProcessor processor : searchRunProcessors ) {
					try {
						processor.process( searchRunReplies, this );
					}
					catch ( Exception e ) {
						String s = bundle.getString( "exc_srp", processor.getDescription(), e.getMessage() );
						logger.error( s, e );
						gui.addMessage( new StatusMessage( s, StatusMessageSeverity.ERROR ) );
					}
				}
			}
			catch ( Exception e ) {
				logger.error( "cannot get results", e );
			}

			gui.enableRunFunction( true );
		}
	}

	public AppGUI( IResourceBundleWithFormatting bundle,
					IStorage storage,
					IPreferencesFactory prefsFactory,
					IPreferences prefs,
					IWebDriverFactory webDriverFactory,
					ISearchRunFactory searchRunFactory,
					ISnapshotFactory snapshotFactory,
					ITweetFactory tweetFactory,
					List<ISearchRunRepliesProcessor> searchRunProcessors ) throws Exception {
		this.bundle = bundle;
		this.storage = storage;
		this.prefsFactory = prefsFactory;
		this.prefs = prefs;
		this.webDriverFactory = webDriverFactory;
		this.searchRunFactory = searchRunFactory;
		this.snapshotFactory = snapshotFactory;
		this.tweetFactory = tweetFactory;
		this.searchRunProcessors = searchRunProcessors;
	}

	public void run() throws Exception {
		try {
			//UIManager.setLookAndFeel( UIManager.getSystemLookAndFeelClassName() );
			//UIManager.setLookAndFeel( "javax.swing.plaf.nimbus.NimbusLookAndFeel" );
			//UIManager.setLookAndFeel( "com.pagosoft.plaf.PgsLookAndFeel" );
			UIManager.setLookAndFeel( "com.seaglasslookandfeel.SeaGlassLookAndFeel" );
		}
		catch (Exception e) {
			e.printStackTrace();
		}

		buildGUIElements();

		gui = new MainGUI( bundle, prefs, guiElements );

		gui.addRunEventListener( this );
		gui.addPreferencesEventListener( this );
		gui.addWindowClosingEventListener( this );

		SwingUtilities.invokeLater( new Runnable() {
			public void run() {
				gui.showGUI();
				checkPreferences();
			}
		});
	}

	@Override
	public void runEventFired( RunEvent runEvent ) {
		gui.enableRunFunction( false );

		SwingWorker<ISearchRunReplies, StatusMessage> worker = new Worker();

		worker.execute();
	}

	@Override
	public void preferencesEventFired( PreferencesEvent preferencesEvent ) {
		Map<String,String> map = preferencesEvent.getUserdata();
		for ( String key : map.keySet() ) {
			prefs.setValue( key, map.get( key ) );
		}

		try {
			prefs.save();
			checkPreferences();
		}
		catch ( Exception e ) {
			String s = bundle.getString( "exc_cannot_save_prefs", "" + Utils.sanitizeMap( prefs.getValues() ) );
			logger.error( s, e );
			gui.addMessage( new StatusMessage( s, StatusMessageSeverity.ERROR ) );
		}
	}

	@Override
	public void windowClosingEventFired( WindowClosingEvent windowClosingEvent ) {
		windowClosingEvent.getWindow().dispose();

		logger.info( "DONE" );
		System.exit( 0 );
	}

	private void checkPreferences() {
		if ( Utils.isEmpty( prefs.getValue( "prefs.testing_account_name_private" ) ) ||
				Utils.isEmpty( prefs.getValue( "prefs.testing_account_password_private" ) ) ) {
			gui.addMessage( new StatusMessage( bundle.getString( "prefs_msg_no_user" ), StatusMessageSeverity.WARN ) );
		}

		if ( Utils.isStringTrue( prefs.getValue( "prefs.upload_results" ) ) ) {
			gui.addMessage( new StatusMessage( bundle.getString( "prefs_msg_upload_results" ), StatusMessageSeverity.WARN ) );
		}

		if ( Utils.isStringTrue( prefs.getValue( "prefs.prefs.make_results_public" ) ) ) {
			gui.addMessage( new StatusMessage( bundle.getString( "prefs_msg_make_results_public" ), StatusMessageSeverity.WARN ) );
		}

		if ( Utils.isEmpty( prefs.getValue( "prefs.firefox_path_app" ) ) ||
				Utils.isEmpty( prefs.getValue( "prefs.firefox_path_profile" ) ) ) {
			gui.addMessage( new StatusMessage( bundle.getString( "prefs_msg_no_ffbin" ), StatusMessageSeverity.WARN ) );
		}

		String handle = prefs.getValue( "prefs.handle_to_check" );
		if ( Utils.isEmpty( handle ) ) {
			gui.addMessage( new StatusMessage( bundle.getString( "prefs_msg_no_handle" ), StatusMessageSeverity.ERROR ) );
			gui.enableRunFunction( false );
		}
		else {
			gui.addMessage( new StatusMessage( bundle.getString( "prefs_msg_handle", handle ), StatusMessageSeverity.INFO ) );
			gui.enableRunFunction( true );
		}
	}

	private void buildGUIElements() throws Exception {
		guiElements = new ArrayList<ElementDescriptor>( 15 );

		guiElements.add( new ElementDescriptor( "textfield", "prefs.handle_to_check",
													bundle.getString( "prefs_element_handle_to_check_name" ),
													bundle.getString( "prefs_element_handle_to_check_help" ), 30 ) );
		guiElements.add( new ElementDescriptor( "textfield", "prefs.testing_account_name_private",
													bundle.getString( "prefs_element_testing_account_name" ),
													bundle.getString( "prefs_element_testing_account_help" ), 30 ) );
		guiElements.add( new ElementDescriptor( "password", "prefs.testing_account_password_private",
													bundle.getString( "prefs_element_testing_account_password_name" ),
													bundle.getString( "prefs_element_testing_account_password_help" ), 30 ) );
		guiElements.add( new ElementDescriptor( "textfield", "prefs.num_tweets_to_check",
													bundle.getString( "prefs_element_num_tweets_to_check_name" ),
													bundle.getString( "prefs_element_num_tweets_to_check_help" ), 30 ) );
		guiElements.add( new ElementDescriptor( "textfield", "prefs.num_timeline_pages_to_check",
													bundle.getString( "prefs_element_num_timeline_pages_to_check_name" ),
													bundle.getString( "prefs_element_num_timeline_pages_to_check_help" ), 30 ) );
		guiElements.add( new ElementDescriptor( "textfield", "prefs.num_individual_pages_to_check",
													bundle.getString( "prefs_element_num_individual_pages_to_check_name" ),
													bundle.getString( "prefs_element_num_individual_pages_to_check_help" ), 30 ) );
		guiElements.add( new ElementDescriptor( "checkbox", "prefs.upload_results",
													bundle.getString( "prefs_element_upload_results_name" ),
													bundle.getString( "prefs_element_upload_results_help" ), 30 ) );
		guiElements.add( new ElementDescriptor( "checkbox", "prefs.make_results_public",
													bundle.getString( "prefs_element_make_results_public_name" ),
													bundle.getString( "prefs_element_make_results_public_help" ), 30 ) );
		guiElements.add( new ElementDescriptor( "textfield", "prefs.user_email",
													bundle.getString( "prefs_element_user_email_name" ),
													bundle.getString( "prefs_element_user_email_help" ), 30 ) );
		guiElements.add( new ElementDescriptor( "textfield", "prefs.firefox_path_app",
													bundle.getString( "prefs_element_firefox_path_app_name" ),
													bundle.getString( "prefs_element_firefox_path_app_help" ), 30 ) );
		guiElements.add( new ElementDescriptor( "textfield", "prefs.firefox_path_profile",
													bundle.getString( "prefs_element_firefox_path_profile_name" ),
													bundle.getString( "prefs_element_firefox_path_profile_help" ), 30 ) );
	}
}

