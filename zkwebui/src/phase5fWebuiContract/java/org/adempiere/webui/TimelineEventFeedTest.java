package org.adempiere.webui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.awt.Color;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.compiere.model.MAssignmentSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Phase 5f timeline inherited route")
class TimelineEventFeedTest {

	@Test
	@DisplayName("GET requires the complete reviewed query without touching the database")
	void incompleteGetHasNoDatabaseEffect() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		AtomicInteger reads = new AtomicInteger();
		TimelineEventFeed servlet = testServlet(reads, new MAssignmentSlot[0]);

		when(request.getParameter("S_Resource_ID")).thenReturn("100");

		servlet.doGet(request, response);

		assertEquals(0, reads.get(), "an incomplete request must not query AD_RecentItem");
		verifyNoInteractions(response);
	}

	@Test
	@DisplayName("the servlet implements only the reviewed GET method")
	void onlyGetIsImplemented() {
		assertEquals(
				List.of("doGet"),
				Arrays.stream(TimelineEventFeed.class.getDeclaredMethods())
						.map(method -> method.getName())
						.filter(name -> name.startsWith("do"))
						.sorted()
						.toList());
	}

	@Test
	@DisplayName("GET emits escaped XML from one read-only assignment query")
	void validGetEmitsXmlFromOneRead() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		StringWriter body = new StringWriter();
		when(response.getWriter()).thenReturn(new PrintWriter(body));
		when(request.getParameter("S_Resource_ID")).thenReturn("100");
		when(request.getParameter("uuid")).thenReturn("widget-1");
		when(request.getParameter("tlid")).thenReturn("timeline-1");
		when(request.getParameter("date"))
				.thenReturn(DateFormat.getInstance().format(new Date(0)));

		MAssignmentSlot slot = mock(MAssignmentSlot.class);
		when(slot.getStartTime()).thenReturn(new Timestamp(1_000));
		when(slot.getEndTime()).thenReturn(new Timestamp(2_000));
		when(slot.getColor(true)).thenReturn(new Color(0x12, 0x34, 0x56));
		when(slot.getName()).thenReturn("Reviewed assignment");
		when(slot.getDescription()).thenReturn("<reviewed>");
		AtomicInteger reads = new AtomicInteger();

		testServlet(reads, new MAssignmentSlot[] {slot}).doGet(request, response);

		assertEquals(1, reads.get(), "timeline is a single read-only database operation");
		verify(response).setContentType("application/xml");
		String xml = body.toString();
		assertTrue(xml.startsWith("<data>\r\n"));
		assertTrue(xml.contains("title=\"Reviewed assignment\""), xml);
		assertTrue(xml.contains("&lt;reviewed&gt;&lt;br/&gt;"), xml);
		assertTrue(xml.endsWith("</data>\r\n"));
		assertFalse(xml.contains("<reviewed>"), "description must not create XML markup");
	}

	private static TimelineEventFeed testServlet(
			AtomicInteger reads, MAssignmentSlot[] slots) {
		return new TimelineEventFeed() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void prepareContext(HttpServletRequest request) {
				// The container/session integration is covered by the Phase 5e gates.
			}

			@Override
			protected MAssignmentSlot[] getAssignmentSlots(
					int resourceId, Timestamp startDate, Timestamp endDate) {
				assertEquals(100, resourceId);
				assertTrue(startDate.before(endDate));
				reads.incrementAndGet();
				return slots;
			}
		};
	}
}
