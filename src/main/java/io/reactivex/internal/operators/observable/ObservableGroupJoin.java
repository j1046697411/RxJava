/**
 * Copyright 2016 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.reactivex.internal.operators.observable;

import static io.reactivex.Flowable.bufferSize;

import java.util.*;
import java.util.concurrent.atomic.*;

import io.reactivex.Observable;
import io.reactivex.ObservableConsumable;
import io.reactivex.Observer;
import io.reactivex.disposables.*;
import io.reactivex.functions.*;
import io.reactivex.internal.disposables.DisposableHelper;
import io.reactivex.internal.functions.Objects;
import io.reactivex.internal.queue.SpscLinkedArrayQueue;
import io.reactivex.internal.util.Exceptions;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.subjects.UnicastSubject;

public class ObservableGroupJoin<TLeft, TRight, TLeftEnd, TRightEnd, R> extends ObservableSource<TLeft, R> {

    final ObservableConsumable<? extends TRight> other;
    
    final Function<? super TLeft, ? extends ObservableConsumable<TLeftEnd>> leftEnd;
    
    final Function<? super TRight, ? extends ObservableConsumable<TRightEnd>> rightEnd;
    
    final BiFunction<? super TLeft, ? super Observable<TRight>, ? extends R> resultSelector;
    
    public ObservableGroupJoin(
            ObservableConsumable<TLeft> source, 
            ObservableConsumable<? extends TRight> other,
            Function<? super TLeft, ? extends ObservableConsumable<TLeftEnd>> leftEnd,
            Function<? super TRight, ? extends ObservableConsumable<TRightEnd>> rightEnd,
            BiFunction<? super TLeft, ? super Observable<TRight>, ? extends R> resultSelector) {
        super(source);
        this.other = other;
        this.leftEnd = leftEnd;
        this.rightEnd = rightEnd;
        this.resultSelector = resultSelector;
    }

    @Override
    protected void subscribeActual(Observer<? super R> s) {

        GroupJoinSubscription<TLeft, TRight, TLeftEnd, TRightEnd, R> parent = 
                new GroupJoinSubscription<TLeft, TRight, TLeftEnd, TRightEnd, R>(s, leftEnd, rightEnd, resultSelector);
        
        s.onSubscribe(parent);
        
        LeftRightSubscriber left = new LeftRightSubscriber(parent, true);
        parent.disposables.add(left);
        LeftRightSubscriber right = new LeftRightSubscriber(parent, false);
        parent.disposables.add(right);
        
        source.subscribe(left);
        other.subscribe(right);
    }

    interface JoinSupport {
        
        void innerError(Throwable ex);
        
        void innerComplete(LeftRightSubscriber sender);
        
        void innerValue(boolean isLeft, Object o);
        
        void innerClose(boolean isLeft, LeftRightEndSubscriber index);
        
        void innerCloseError(Throwable ex);
    }

    static final class GroupJoinSubscription<TLeft, TRight, TLeftEnd, TRightEnd, R> 
    extends AtomicInteger implements Disposable, JoinSupport {

        /** */
        private static final long serialVersionUID = -6071216598687999801L;

        final Observer<? super R> actual;
        
        final SpscLinkedArrayQueue<Object> queue;
        
        final CompositeDisposable disposables;
        
        final Map<Integer, UnicastSubject<TRight>> lefts;
        
        final Map<Integer, TRight> rights;

        final AtomicReference<Throwable> error;
        
        final Function<? super TLeft, ? extends ObservableConsumable<TLeftEnd>> leftEnd;
        
        final Function<? super TRight, ? extends ObservableConsumable<TRightEnd>> rightEnd;
        
        final BiFunction<? super TLeft, ? super Observable<TRight>, ? extends R> resultSelector;
        
        final AtomicInteger active;
        
        int leftIndex;
        
        int rightIndex;

        volatile boolean cancelled;
        
        static final Integer LEFT_VALUE = 1;
        
        static final Integer RIGHT_VALUE = 2;
        
        static final Integer LEFT_CLOSE = 3;
        
        static final Integer RIGHT_CLOSE = 4;
        
        public GroupJoinSubscription(
                Observer<? super R> actual, 
                Function<? super TLeft, ? extends ObservableConsumable<TLeftEnd>> leftEnd,
                Function<? super TRight, ? extends ObservableConsumable<TRightEnd>> rightEnd,
                BiFunction<? super TLeft, ? super Observable<TRight>, ? extends R> resultSelector) {
            this.actual = actual;
            this.disposables = new CompositeDisposable();
            this.queue = new SpscLinkedArrayQueue<Object>(bufferSize());
            this.lefts = new LinkedHashMap<Integer, UnicastSubject<TRight>>();
            this.rights = new LinkedHashMap<Integer, TRight>();
            this.error = new AtomicReference<Throwable>();
            this.leftEnd = leftEnd;
            this.rightEnd = rightEnd;
            this.resultSelector = resultSelector;
            this.active = new AtomicInteger(2);
        }
        
        @Override
        public void dispose() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            cancelAll();
            if (getAndIncrement() == 0) {
                queue.clear();
            }
        }
        
        @Override
        public boolean isDisposed() {
            return cancelled;
        }
        
        void cancelAll() {
            disposables.dispose();
        }
        
        void errorAll(Observer<?> a) {
            Throwable ex = Exceptions.terminate(error);
            
            for (UnicastSubject<TRight> up : lefts.values()) {
                up.onError(ex);
            }
            
            lefts.clear();
            rights.clear();
            
            a.onError(ex);
        }
        
        void fail(Throwable exc, Observer<?> a, Queue<?> q) {
            Exceptions.throwIfFatal(exc);
            Exceptions.addThrowable(error, exc);
            q.clear();
            cancelAll();
            errorAll(a);
        }
        
        void drain() {
            if (getAndIncrement() != 0) {
                return;
            }
            
            int missed = 1;
            SpscLinkedArrayQueue<Object> q = queue;
            Observer<? super R> a = actual;
            
            for (;;) {
                for (;;) {
                    if (cancelled) {
                        q.clear();
                        return;
                    }
                    
                    Throwable ex = error.get();
                    if (ex != null) {
                        q.clear();
                        cancelAll();
                        errorAll(a);
                        return;
                    }
                    
                    boolean d = active.get() == 0;
                    
                    Integer mode = (Integer)q.poll();
                    
                    boolean empty = mode == null;
                    
                    if (d && empty) {
                        for (UnicastSubject<?> up : lefts.values()) {
                            up.onComplete();
                        }

                        lefts.clear();
                        rights.clear();
                        disposables.dispose();
                        
                        a.onComplete();
                        return;
                    }
                    
                    if (empty) {
                        break;
                    }
                    
                    Object val = q.poll();
                    
                    if (mode == LEFT_VALUE) {
                        @SuppressWarnings("unchecked")
                        TLeft left = (TLeft)val;
                        
                        UnicastSubject<TRight> up = UnicastSubject.create();
                        int idx = leftIndex++;
                        lefts.put(idx, up);
                        
                        ObservableConsumable<TLeftEnd> p;
                        
                        try {
                            p = Objects.requireNonNull(leftEnd.apply(left), "The leftEnd returned a null Publisher");
                        } catch (Throwable exc) {
                            fail(exc, a, q);
                            return;
                        }
                        
                        LeftRightEndSubscriber end = new LeftRightEndSubscriber(this, true, idx);
                        disposables.add(end);
                        
                        p.subscribe(end);
                        
                        ex = error.get();
                        if (ex != null) {
                            q.clear();
                            cancelAll();
                            errorAll(a);
                            return;
                        }
                        
                        R w;
                        
                        try {
                            w = Objects.requireNonNull(resultSelector.apply(left, up), "The resultSelector returned a null value");
                        } catch (Throwable exc) {
                            fail(exc, a, q);
                            return;
                        }
                        
                        a.onNext(w);
                        
                        for (TRight right : rights.values()) {
                            up.onNext(right);
                        }
                    } 
                    else if (mode == RIGHT_VALUE) {
                        @SuppressWarnings("unchecked")
                        TRight right = (TRight)val;
                        
                        int idx = rightIndex++;
                        
                        rights.put(idx, right);
                        
                        ObservableConsumable<TRightEnd> p;
                        
                        try {
                            p = Objects.requireNonNull(rightEnd.apply(right), "The rightEnd returned a null Publisher");
                        } catch (Throwable exc) {
                            fail(exc, a, q);
                            return;
                        }
                        
                        LeftRightEndSubscriber end = new LeftRightEndSubscriber(this, false, idx);
                        disposables.add(end);
                        
                        p.subscribe(end);
                        
                        ex = error.get();
                        if (ex != null) {
                            q.clear();
                            cancelAll();
                            errorAll(a);
                            return;
                        }
                        
                        for (UnicastSubject<TRight> up : lefts.values()) {
                            up.onNext(right);
                        }
                    }
                    else if (mode == LEFT_CLOSE) {
                        LeftRightEndSubscriber end = (LeftRightEndSubscriber)val;
                        
                        UnicastSubject<TRight> up = lefts.remove(end.index);
                        disposables.remove(end);
                        if (up != null) {
                            up.onComplete();
                        }
                    }
                    else if (mode == RIGHT_CLOSE) {
                        LeftRightEndSubscriber end = (LeftRightEndSubscriber)val;
                        
                        rights.remove(end.index);
                        disposables.remove(end);
                    }
                }

                missed = addAndGet(-missed);
                if (missed == 0) {
                    break;
                }
            }
        }
        
        @Override
        public void innerError(Throwable ex) {
            if (Exceptions.addThrowable(error, ex)) {
                active.decrementAndGet();
                drain();
            } else {
                RxJavaPlugins.onError(ex);
            }
        }
        
        @Override
        public void innerComplete(LeftRightSubscriber sender) {
            disposables.delete(sender);
            active.decrementAndGet();
            drain();
        }
        
        @Override
        public void innerValue(boolean isLeft, Object o) {
            synchronized (this) {
                queue.offer(isLeft ? LEFT_VALUE : RIGHT_VALUE, o);
            }
            drain();
        }
        
        @Override
        public void innerClose(boolean isLeft, LeftRightEndSubscriber index) {
            synchronized (this) {
                queue.offer(isLeft ? LEFT_CLOSE : RIGHT_CLOSE, index);
            }
            drain();
        }
        
        @Override
        public void innerCloseError(Throwable ex) {
            if (Exceptions.addThrowable(error, ex)) {
                drain();
            } else {
                RxJavaPlugins.onError(ex);
            }
        }
    }
    
    static final class LeftRightSubscriber 
    extends AtomicReference<Disposable>
    implements Observer<Object>, Disposable {
        /** */
        private static final long serialVersionUID = 1883890389173668373L;

        final JoinSupport parent;

        final boolean isLeft;
        
        public LeftRightSubscriber(JoinSupport parent, boolean isLeft) {
            this.parent = parent;
            this.isLeft = isLeft;
        }
        
        @Override
        public void dispose() {
            DisposableHelper.dispose(this);
        }

        @Override
        public boolean isDisposed() {
            return DisposableHelper.isDisposed(get());
        }

        @Override
        public void onSubscribe(Disposable s) {
            DisposableHelper.setOnce(this, s);
        }

        @Override
        public void onNext(Object t) {
            parent.innerValue(isLeft, t);
        }

        @Override
        public void onError(Throwable t) {
            parent.innerError(t);
        }

        @Override
        public void onComplete() {
            parent.innerComplete(this);
        }
        
    }
    
    static final class LeftRightEndSubscriber 
    extends AtomicReference<Disposable>
    implements Observer<Object>, Disposable {
        /** */
        private static final long serialVersionUID = 1883890389173668373L;

        final JoinSupport parent;

        final boolean isLeft;
        
        final int index;
        
        public LeftRightEndSubscriber(JoinSupport parent, 
                boolean isLeft, int index) {
            this.parent = parent;
            this.isLeft = isLeft;
            this.index = index;
        }
        
        @Override
        public void dispose() {
            DisposableHelper.dispose(this);
        }

        @Override
        public boolean isDisposed() {
            return DisposableHelper.isDisposed(get());
        }

        @Override
        public void onSubscribe(Disposable s) {
            DisposableHelper.setOnce(this, s);
        }

        @Override
        public void onNext(Object t) {
            if (DisposableHelper.dispose(this)) {
                parent.innerClose(isLeft, this);
            }
        }

        @Override
        public void onError(Throwable t) {
            parent.innerError(t);
        }

        @Override
        public void onComplete() {
            parent.innerClose(isLeft, this);
        }
        
    }

}
