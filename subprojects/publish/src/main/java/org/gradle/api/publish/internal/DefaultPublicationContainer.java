/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.publish.internal;

import com.google.common.collect.Maps;
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NamedDomainObjectFactory;
import org.gradle.api.UnknownDomainObjectException;
import org.gradle.api.internal.ClosureBackedAction;
import org.gradle.api.internal.DefaultPolymorphicDomainObjectContainer;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.PublicationContainer;
import org.gradle.internal.ImmutableActionSet;
import org.gradle.internal.reflect.Instantiator;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

public class DefaultPublicationContainer extends DefaultPolymorphicDomainObjectContainer<Publication> implements PublicationContainer {

    private final Map<Class<?>, Class<?>> apiToImpl = Maps.newHashMap();

    public DefaultPublicationContainer(Instantiator instantiator) {
        super(Publication.class, instantiator);
    }

    @Override
    protected void handleAttemptToAddItemWithNonUniqueName(Publication o) {
        throw new InvalidUserDataException(String.format("Publication with name '%s' added multiple times", o.getName()));
    }

    public Publication create(String name, Action<? super Publication> configureAction) throws InvalidUserDataException {
        assertCanAdd(name);
        Publication object = doCreate(name);
        add(object);
        if (configureAction != null) {
            ((LazyPublication) object).configureLazily(configureAction);
        }
        return object;
    }

    public <U extends Publication> U create(String name, Class<U> type, Action<? super U> configuration) {
        assertCanAdd(name);
        U object = doCreate(name, type);
        add(object);
        if (configuration != null) {
            ((LazyPublication) object).configureLazily(configuration);
        }
        return object;
    }

    @Override
    protected Publication doCreate(String name) {
        return doCreate(name, getType());
    }

    @Override
    protected <U extends Publication> U doCreate(String name, Class<U> type) {
        Class<?> implType = apiToImpl.get(type);
        return (U) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{implType != null ? implType : type, LazyPublication.class}, new LazyPublicationInvocationHandler(name, type));
    }

    @Override
    public Publication getByName(String name, Action<? super Publication> configureAction) throws UnknownDomainObjectException {
        LazyPublication publication = (LazyPublication) super.getByName(name, configureAction);
        publication.configureLazily(configureAction);
        return publication;
    }

    @Override
    public Publication getByName(String name, Closure configureClosure) throws UnknownDomainObjectException {
        return getByName(name, new ClosureBackedAction<Publication>(configureClosure));
    }

    @Override
    public <U extends Publication, V extends U> void registerFactory(Class<U> type, Class<V> internalType, NamedDomainObjectFactory<? extends U> factory) {
        apiToImpl.put(type, internalType);
        super.registerFactory(type, factory);
    }

    private interface LazyPublication extends PublicationInternal {
        void configureLazily(Action<?> action);
    }

    private final class LazyPublicationInvocationHandler implements InvocationHandler {

        private final String name;
        private final Class<? extends Publication> type;
        private Publication delegate;
        private ImmutableActionSet<? super Publication> actions = ImmutableActionSet.empty();

        private LazyPublicationInvocationHandler(String name, Class<? extends Publication> type) {
            this.name = name;
            this.type = type;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("getName") && method.getParameterTypes().length == 0) {
                return name;
            } else if (method.getName().equals("equals") && method.getParameterTypes().length == 1) {
                return false;
            } else if (method.getName().equals("hashCode") && method.getParameterTypes().length == 0) {
                return 0;
            } else if (method.getName().equals("configureLazily")) {
                Action action = (Action) args[0];
                configureLazily(action);
                return null;
            } else {
                initDelegate();
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }

        private void configureLazily(Action action) {
            if (delegate == null) {
                actions = actions.add(action);
            } else {
                action.execute(delegate);
            }
        }

        private void initDelegate() {
            if (delegate == null) {
                delegate = DefaultPublicationContainer.super.doCreate(name, type);
                actions.execute(delegate);
            }
        }
    }
}
