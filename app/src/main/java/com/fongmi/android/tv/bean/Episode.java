package com.fongmi.android.tv.bean;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.utils.Trans;
import com.google.gson.annotations.SerializedName;

import java.util.Objects;

public class Episode implements Parcelable, Diffable<Episode> {

    @SerializedName("name")
    private String name;
    @SerializedName("desc")
    private String desc;
    @SerializedName("url")
    private String url;
    private transient String displayName;

    private int index;
    private int number;
    private boolean selected;
    private TmdbEpisode tmdbEpisode;
    private transient boolean tmdbEpisodeMapped;

    private Episode(String name, String desc, String url) {
        this.number = Util.getEpisodeNumber(name);
        this.name = name;
        this.desc = desc;
        this.url = url;
    }

    public Episode() {
    }

    protected Episode(Parcel in) {
        this.name = in.readString();
        this.desc = in.readString();
        this.url = in.readString();
        this.number = in.readInt();
        this.selected = in.readByte() != 0;
    }

    public static Episode create(String name, String url) {
        return new Episode(name, "", url).trans();
    }

    public static Episode create(String name, String desc, String url) {
        return new Episode(name, desc, url).trans();
    }

    public String getName() {
        return isEmpty(name) ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDesc() {
        return isEmpty(desc) ? "" : desc;
    }

    public String getRawDisplayName() {
        return getDesc().concat(getName());
    }

    public String getDisplayName() {
        return isEmpty(displayName) ? getRawDisplayName() : displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getUrl() {
        return isEmpty(url) ? "" : url;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public int getNumber() {
        return number;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void deselect() {
        setSelected(false);
    }

    public TmdbEpisode getTmdbEpisode() {
        return tmdbEpisode;
    }

    public void setTmdbEpisode(TmdbEpisode tmdbEpisode) {
        this.tmdbEpisode = tmdbEpisode;
        this.tmdbEpisodeMapped = false;
    }

    public void setMappedTmdbEpisode(TmdbEpisode tmdbEpisode) {
        this.tmdbEpisode = tmdbEpisode;
        this.tmdbEpisodeMapped = tmdbEpisode != null;
    }

    public boolean isTmdbEpisodeMapped() {
        return tmdbEpisodeMapped;
    }

    public int getScore(String name, int number) {
        if (getName().equalsIgnoreCase(name)) return 100;
        if (number != -1 && getNumber() == number) return 80;
        if (number == -1 && name.length() >= 2 && getName().toLowerCase().contains(name.toLowerCase())) return 70;
        if (number == -1 && getName().length() >= 2 && name.toLowerCase().contains(getName().toLowerCase())) return 60;
        return 0;
    }

    public boolean matchesName(Episode other) {
        if (other == null) return false;
        return getName().equalsIgnoreCase(other.getName());
    }

    /**
     * 按集号匹配：不同线路/不同源对同一集的命名格式往往不同（如“第9集”与“[277.1MB] 9. xxx”），
     * URL 与集名严格比对都会失败。已绑定 TMDB 时优先比较标准季集位置；否则沿用
     * Flag.find/Episode.getNumber 的现有集号提取结果。
     */
    public boolean matchesNumber(Episode other) {
        if (other == null) return false;
        TmdbEpisode mineTmdb = getTmdbEpisode();
        TmdbEpisode theirsTmdb = other.getTmdbEpisode();
        if (mineTmdb != null && theirsTmdb != null
                && mineTmdb.getNumber() > 0 && theirsTmdb.getNumber() > 0
                && mineTmdb.getSeasonNumber() >= 0 && theirsTmdb.getSeasonNumber() >= 0
                && mineTmdb.getSeasonNumber() != theirsTmdb.getSeasonNumber()) return false;
        int mine = getMatchNumber();
        int theirs = other.getMatchNumber();
        return mine > 0 && theirs > 0 && mine == theirs;
    }

    private int getMatchNumber() {
        if (getTmdbEpisode() != null && getTmdbEpisode().getNumber() > 0) return getTmdbEpisode().getNumber();
        return getNumber() > 0 ? getNumber() : Util.getEpisodeNumber(getName());
    }

    public boolean matches(Episode other) {
        if (other == null) return false;
        if (hasTmdbEpisodeNumber() && other.hasTmdbEpisodeNumber()) {
            if (!matchesNumber(other)) return false;
            return !sameConfirmedTmdbSeason(other) || sameTmdbVariant(other);
        }
        if (!isEmpty(getUrl()) && !isEmpty(other.getUrl())) return getUrl().equals(other.getUrl());
        return matchesName(other);
    }

    /**
     * 播放恢复时判断是否仍是同一集。源站刷新后 URL 可能变化，
     * 因此在严格 URL 匹配失败时回退到集名和集号。
     * <p>
     * 注意：当双方都已绑定 TMDB 季集号，且季号都已知并相等时，同一集可能存在多个版本
     * （同一 flag 内同名但 URL 不同、或源站用同集名的不同线路条目）。此时必须先在版本层消歧，
     * 否则“集号相同即同集”会把第二版本误认成第一版本（用户点第二个版本，回到播放页却选中/续播
     * 第一个版本）。任一侧季号未知时无法断定是同集的不同版本，退回原有的集号容错语义。
     */
    public boolean matchesPlayback(Episode other) {
        if (other == null) return false;
        if (hasTmdbEpisodeNumber() && other.hasTmdbEpisodeNumber()) {
            if (!matchesNumber(other)) return false;
            return !sameConfirmedTmdbSeason(other) || sameTmdbVariant(other);
        }
        if (!isEmpty(getUrl()) && !isEmpty(other.getUrl()) && getUrl().equals(other.getUrl())) return true;
        if (!isEmpty(getName()) && !isEmpty(other.getName()) && matchesName(other)) return true;
        return matchesNumber(other);
    }

    /**
     * 双方是否都绑定了已知的 TMDB 季号且相等。只有这种“同一季同一集”的确认状态下，
     * 才允许用 {@link #sameTmdbVariant(Episode)} 区分同集多版本；季号任一侧未知时，
     * TMDB 集号相同既可能是同集的不同版本，也可能是跨源续播的同一集，无法断言。
     */
    private boolean sameConfirmedTmdbSeason(Episode other) {
        int mine = getTmdbEpisode().getSeasonNumber();
        int theirs = other.getTmdbEpisode().getSeasonNumber();
        return mine >= 0 && theirs >= 0 && mine == theirs;
    }

    /**
     * 同一 TMDB 季集号下的版本消歧。
     * <p>
     * 优先级：URL 精确相等 &gt; 原始条目文本（{@link #getRawDisplayName()}，即 desc+源站集名）相等 &gt;
     * 双方都没有任何版本信息可区分时视为同一集。
     * <p>
     * 这里刻意不使用 {@link #getDisplayName()}：它是可被刮削标题覆盖的展示字段，不能作为版本身份。
     */
    private boolean sameTmdbVariant(Episode other) {
        if (!isEmpty(getUrl()) || !isEmpty(other.getUrl())) {
            if (isEmpty(getUrl()) || isEmpty(other.getUrl())) return false;
            return getUrl().equals(other.getUrl());
        }
        String mine = getRawDisplayName();
        String theirs = other.getRawDisplayName();
        if (!mine.isEmpty() || !theirs.isEmpty()) {
            return !mine.isEmpty() && !theirs.isEmpty() && mine.equalsIgnoreCase(theirs);
        }
        return true;
    }

    private boolean hasTmdbEpisodeNumber() {
        return getTmdbEpisode() != null && getTmdbEpisode().getNumber() > 0;
    }

    private boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }

    public Episode trans() {
        if (Trans.pass()) return this;
        this.name = Trans.s2t(name);
        this.desc = Trans.s2t(desc);
        return this;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Episode it)) return false;
        return Objects.equals(getName(), it.getName()) && Objects.equals(getUrl(), it.getUrl());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), getUrl());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.name);
        dest.writeString(this.desc);
        dest.writeString(this.url);
        dest.writeInt(this.number);
        dest.writeByte(this.selected ? (byte) 1 : (byte) 0);
    }

    @Override
    public boolean isSameItem(Episode other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(Episode other) {
        return getUrl().equals(other.getUrl()) && getDesc().equals(other.getDesc());
    }

    public record Rule(Episode episode, int score) {

        public boolean find() {
            return score > 0;
        }
    }

    public static final Creator<Episode> CREATOR = new Creator<>() {
        @Override
        public Episode createFromParcel(Parcel source) {
            return new Episode(source);
        }

        @Override
        public Episode[] newArray(int size) {
            return new Episode[size];
        }
    };
}
