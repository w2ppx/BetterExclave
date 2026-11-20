/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.internal;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.sagernet.database.ProxyEntity;
import io.nekohasekai.sagernet.database.SagerDatabase;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class BalancerBean extends InternalBean {

    public static final int TYPE_LIST = 0;
    public static final int TYPE_GROUP = 1;

    public Integer type;
    public String strategy;
    public List<Long> proxies;
    public Long groupId;

    public String probeUrl;
    public Integer probeInterval;
    public String nameFilter;
    public String nameFilter1;
    public Boolean useLandingProxy;
    public Boolean useFrontProxy;
    /**
     * A list balancer's fallbackTag: the proxy used only once none of {@link #proxies} can be
     * picked. It is not a balancing candidate itself. 0 when unset.
     */
    public Long fallbackProxy;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (name == null) name = "";
        if (strategy == null) strategy = "";
        if (type == null) type = TYPE_LIST;
        if (proxies == null) proxies = new ArrayList<>();
        if (groupId == null) groupId = 0L;
        if (probeUrl == null) probeUrl = "";
        if (probeInterval == null) probeInterval = 300;
        if (nameFilter == null) nameFilter = "";
        if (nameFilter1 == null) nameFilter1 = "";
        if (useLandingProxy == null) useLandingProxy = false;
        if (useFrontProxy == null) useFrontProxy = false;
        if (fallbackProxy == null) fallbackProxy = 0L;
    }

    /**
     * A list balancer's proxies in order, followed by its {@link #fallbackProxy} when that is
     * set and not already a member.
     */
    public List<Long> listMemberIds() {
        List<Long> ids = new ArrayList<>();
        for (Long id : proxies) {
            if (!ids.contains(id)) ids.add(id);
        }
        if (fallbackProxy != null && fallbackProxy > 0 && !ids.contains(fallbackProxy)) {
            ids.add(fallbackProxy);
        }
        return ids;
    }

    @Override
    public String displayName() {
        if (!name.isEmpty()) {
            return name;
        } else {
            return "Balancer " + Math.abs(hashCode());
        }
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(6);
        output.writeInt(type);
        output.writeString(strategy);
        switch (type) {
            case TYPE_LIST: {
                int length = proxies.size();
                output.writeInt(length);
                for (Long proxy : proxies) {
                    output.writeLong(proxy);
                }
                break;
            }
            case TYPE_GROUP: {
                output.writeLong(groupId);
                break;
            }
        }
        output.writeString(probeUrl);
        output.writeInt(probeInterval);
        if (type == TYPE_GROUP) {
            output.writeString(nameFilter);
            output.writeString(nameFilter1);
            output.writeBoolean(useLandingProxy);
            output.writeBoolean(useFrontProxy);
        }
        output.writeLong(fallbackProxy);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        type = input.readInt();
        strategy = input.readString();
        switch (type) {
            case TYPE_LIST: {
                int length = input.readInt();
                proxies = new ArrayList<>();
                for (int i = 0; i < length; i++) {
                    proxies.add(input.readLong());
                }
                break;
            }
            case TYPE_GROUP: {
                groupId = input.readLong();
                break;
            }
        }
        if (version >= 1) {
            probeUrl = input.readString();
            probeInterval = input.readInt();
        }
        if (version >= 2 && type == TYPE_GROUP) {
            nameFilter = input.readString();
        }
        if (version >= 3 && type == TYPE_GROUP) {
            nameFilter1 = input.readString();
        }
        if (version >= 4 && type == TYPE_GROUP) {
            useLandingProxy = input.readBoolean();
        }
        if (version >= 5 && type == TYPE_GROUP) {
            useFrontProxy = input.readBoolean();
        }
        if (version >= 6) {
            fallbackProxy = input.readLong();
        }
    }

    @NonNull
    @Override
    public BalancerBean clone() {
        return KryoConverters.deserialize(new BalancerBean(), KryoConverters.serialize(this));
    }

    public static final Creator<BalancerBean> CREATOR = new CREATOR<BalancerBean>() {
        @NonNull
        @Override
        public BalancerBean newInstance() {
            return new BalancerBean();
        }

        @Override
        public BalancerBean[] newArray(int size) {
            return new BalancerBean[size];
        }
    };

    @Override
    public boolean isInsecure() {
        List<ProxyEntity> proxyEntities = SagerDatabase.Companion.getProxyDao().getEntities(listMemberIds());
        if (proxyEntities.isEmpty()) {
            return false;
        }
        try {
            for (ProxyEntity proxyEntity: proxyEntities) {
                if (proxyEntity.requireBean().isInsecure()) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

}
