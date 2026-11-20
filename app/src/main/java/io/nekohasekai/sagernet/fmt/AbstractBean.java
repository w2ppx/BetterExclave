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

package io.nekohasekai.sagernet.fmt;

import static io.nekohasekai.sagernet.fmt.gson.GsonsKt.getGson;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

import io.nekohasekai.sagernet.ExtraType;
import io.nekohasekai.sagernet.ktx.KryosKt;
import io.nekohasekai.sagernet.ktx.NetsKt;

public abstract class AbstractBean extends Serializable {

    public String serverAddress;
    public Integer serverPort;
    public String name;

    /**
     * Serialization version 37 is ambiguous: this fork used it for the splithttp session ID
     * fields, upstream used it for {@code serverNameToVerify}, and builds carrying the merge
     * of both wrote every one of those fields under 37. Blobs written by the fork are the
     * default reading; {@link KryoConverters#deserialize} flips this flag and retries when
     * that reading desynchronizes the stream.
     */
    public transient boolean readServerNameToVerifyOnV37;
    public transient String finalAddress;
    public transient int finalPort;

    public int extraType;
    public String profileId;
    public String profileRoutingRules;
    /**
     * The profile's own {@code dns.hosts}, as a JSON object of domain to address or address
     * list, empty when it has none. Merged under the user's hosts when the config is built.
     */
    public String profileDnsHosts;

    public String displayName() {
        if (!name.isEmpty()) {
            return name;
        } else {
            return displayAddress();
        }
    }

    public String displayAddress() {
        return NetsKt.joinHostPort(NetsKt.wrapIDN(this.serverAddress), this.serverPort);
    }

    public String network() {
        return "tcp,udp";
    }

    public boolean canMapping() {
        return true;
    }

    @Override
    public void initializeDefaultValues() {
        if (serverAddress == null) serverAddress = "127.0.0.1";
        if (serverPort == null) serverPort = 1080;
        if (name == null) name = "";

        finalAddress = serverAddress;
        finalPort = serverPort;

        if (profileId == null) profileId = "";
        if (profileRoutingRules == null) profileRoutingRules = "";
        if (profileDnsHosts == null) profileDnsHosts = "";
    }


    private transient boolean serializeWithoutName;

    @Override
    public void serializeToBuffer(@NonNull ByteBufferOutput output) {
        serialize(output);
        output.writeInt(4);
        if (!serializeWithoutName) {
            output.writeString(name);
        }
        output.writeInt(extraType);
        if (extraType != ExtraType.NONE) {
            output.writeString(profileId);
        }
        output.writeString(profileRoutingRules);
        output.writeString(profileDnsHosts);
    }

    @Override
    public void deserializeFromBuffer(@NonNull ByteBufferInput input) {
        deserialize(input);
        int extraVersion = input.readInt();
        if (extraVersion < 0 || extraVersion > 4) {
            // Never written out of this range: the stream is misaligned, fail before reading
            // a bogus string length out of it.
            throw new KryoException("invalid extra version " + extraVersion);
        }
        name = input.readString();
        extraType = input.readInt();
        if (extraType != ExtraType.NONE) {
            profileId = input.readString();

            if (extraVersion < 2 && extraType == ExtraType.OOCv1) {
                input.readString();
                if (extraVersion >= 1) {
                    input.readString();
                }
                KryosKt.readStringList(input);
            }
        }
        if (extraVersion >= 3) {
            profileRoutingRules = input.readString();
        }
        if (extraVersion >= 4) {
            profileDnsHosts = input.readString();
        }
    }

    public void serialize(ByteBufferOutput output) {
        output.writeString(serverAddress);
        output.writeInt(serverPort);
    }

    public void deserialize(ByteBufferInput input) {
        serverAddress = input.readString();
        serverPort = input.readInt();
    }

    @NotNull
    @Override
    public abstract AbstractBean clone();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        try {
            serializeWithoutName = true;
            ((AbstractBean) o).serializeWithoutName = true;
            return Arrays.equals(KryoConverters.serialize(this), KryoConverters.serialize((AbstractBean) o));
        } finally {
            serializeWithoutName = false;
            ((AbstractBean) o).serializeWithoutName = false;
        }
    }

    @Override
    public int hashCode() {
        try {
            serializeWithoutName = true;
            return Arrays.hashCode(KryoConverters.serialize(this));
        } finally {
            serializeWithoutName = false;
        }
    }

    @NotNull
    @Override
    public String toString() {
        return getClass().getSimpleName() + " " + getGson().toJson(this);
    }

    public void applyFeatureSettings(AbstractBean other) {
    }

    public boolean isInsecure() {
        return false;
    }

}
