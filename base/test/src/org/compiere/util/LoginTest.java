/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 2006-2026 ADempiere Foundation, All Rights Reserved.         *
 * This program is free software, you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY, without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program, if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 *****************************************************************************/
package org.compiere.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class LoginTest
{
	@Test
	void supportsJdk21AndNewer ()
	{
		assertTrue(Login.isSupportedJavaFeature(21));
		assertTrue(Login.isSupportedJavaFeature(27));
	}

	@Test
	void rejectsOlderJdks ()
	{
		assertFalse(Login.isSupportedJavaFeature(20));
		assertFalse(Login.isSupportedJavaFeature(17));
	}

	@Test
	void reportsSupportedJdkRange ()
	{
		assertEquals("21 or newer", Login.getSupportedJavaVersionLabel());
	}
}
