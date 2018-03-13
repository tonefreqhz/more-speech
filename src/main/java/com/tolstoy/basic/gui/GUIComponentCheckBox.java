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
package com.tolstoy.basic.gui;

import javax.swing.JPanel;
import javax.swing.JCheckBox;

public class GUIComponentCheckBox extends GUIComponent {
	private final JCheckBox field;

	public GUIComponentCheckBox( ElementDescriptor desc, boolean selected, JPanel panel, int row ) {
		super( desc );

		field = new JCheckBox( desc.label, selected );

		add( field, panel, row );
	}

	@Override
	public String getValue() {
		return field.isSelected() ? "true" : "false";
	}

	@Override
	public boolean hasLabel() {
		return false;
	}
}

