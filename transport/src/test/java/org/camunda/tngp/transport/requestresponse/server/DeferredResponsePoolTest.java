package org.camunda.tngp.transport.requestresponse.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.util.NoSuchElementException;

import org.camunda.tngp.dispatcher.ClaimedFragment;
import org.camunda.tngp.dispatcher.Dispatcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import org.agrona.concurrent.UnsafeBuffer;

public class DeferredResponsePoolTest
{

    @Mock
    protected Dispatcher dispatcher;

    @Mock
    protected ResponseCompletionHandler completionHandler;

    protected ByteBuffer byteBuffer = ByteBuffer.allocate(2000);

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Before
    public void setUp()
    {
        MockitoAnnotations.initMocks(this);

        // TODO: this smells; perhaps DeferredResponsePool/DeferredResponse should not be handed a full-blown Dispatcher
        //   but a small interface that defines the operation it needs (i.e. providing a buffer of a certain length with
        //   return value true or false if this has worked or not)
        when(dispatcher.claim(any(), anyInt(), anyInt())).thenAnswer(new Answer<Long>()
        {
            @Override
            public Long answer(InvocationOnMock invocation) throws Throwable
            {
                final ClaimedFragment claimedFragment = (ClaimedFragment) invocation.getArguments()[0];
                final int length = (int) invocation.getArguments()[1];
                claimedFragment.wrap(new UnsafeBuffer(new byte[length]), 0, length);

                return 0L;
            }
        });
    }

    @Test
    public void shouldReclaimOutOfOrder()
    {
        // given
        final DeferredResponsePool responsePool = new DeferredResponsePool(dispatcher, 2);

        final DeferredResponse response1 = responsePool.open(1, 2L, 3L);
        final DeferredResponse response2 = responsePool.open(1, 2L, 3L);

        response1.allocate(123);
        response1.defer();

        // when
        responsePool.reclaim(response2);

        // then the response's properties have been reset
        assertThat(response2.channelId).isEqualTo(-1);
        assertThat(response2.connectionId).isEqualTo(-1L);
        assertThat(response2.requestId).isEqualTo(-1L);

        // and it can be reused
        final DeferredResponse reopenedResponse = responsePool.open(1, 2L, 3L);
        assertThat(reopenedResponse).isSameAs(response2);

        // and the other response can be completed
        final DeferredResponse firstDeferred = responsePool.popDeferred();
        assertThat(firstDeferred).isSameAs(response1);
        firstDeferred.commit();
    }

    @Test
    public void shouldAccessDeferredResponsesInFIFO()
    {
        // given
        final DeferredResponsePool responsePool = new DeferredResponsePool(dispatcher, 2);

        final DeferredResponse response1 = responsePool.open(1, 2L, 3L);
        final DeferredResponse response2 = responsePool.open(1, 2L, 3L);

        // when
        response1.defer();
        response2.defer();

        // then
        final DeferredResponse firstDeferred = responsePool.popDeferred();
        final DeferredResponse secondDeferred = responsePool.popDeferred();
        assertThat(firstDeferred).isSameAs(response1);
        assertThat(secondDeferred).isSameAs(response2);
    }

    @Test
    public void shouldNotProvideDeferredResponseWithoutDefer()
    {
        // given
        final DeferredResponsePool responsePool = new DeferredResponsePool(dispatcher, 2);

        // then
        exception.expect(NoSuchElementException.class);

        // when
        responsePool.popDeferred();
    }
}
