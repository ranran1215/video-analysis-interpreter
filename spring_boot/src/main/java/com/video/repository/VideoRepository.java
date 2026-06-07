package com.video.repository;

import com.video.entity.Video;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface VideoRepository extends JpaRepository<Video, Long> {
    Optional<Video> findByFileHash(String fileHash);

    Optional<Video> findTopByUrlHashAndStatusOrderByUploadTimeDesc(String urlHash, String status);
}
