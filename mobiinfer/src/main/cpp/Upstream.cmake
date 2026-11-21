# MobiInfer v0.4.5. Update both values together when changing the source ABI.
set(MOBIINFER_COMMIT "659c3cb5a38493147444a47e0e0762664aacba06")
set(MOBIINFER_ARCHIVE_SHA256 "f415ff541d4d8d5e55d26c3e688ef55c22eeb855be86d0661c1a157e0d741e9f")

function(prepare_mobiinfer output)
    set(cache "${CMAKE_CURRENT_LIST_DIR}/../../../../.gradle/mobiinfer")
    get_filename_component(cache "${cache}" ABSOLUTE)
    file(MAKE_DIRECTORY "${cache}")
    file(LOCK "${cache}/prepare.lock" GUARD FUNCTION TIMEOUT 600)
    set(prefix "Mobiinfer-Android-${MOBIINFER_COMMIT}")
    set(source "${cache}/${prefix}")
    if(NOT EXISTS "${source}/.prepared-${MOBIINFER_ARCHIVE_SHA256}")
        set(archive "${cache}/${MOBIINFER_COMMIT}.zip")
        if(EXISTS "${archive}")
            file(SHA256 "${archive}" actual)
        endif()
        if(NOT actual STREQUAL MOBIINFER_ARCHIVE_SHA256)
            file(DOWNLOAD "https://codeload.github.com/IPADS-SAI/Mobiinfer-Android/zip/${MOBIINFER_COMMIT}"
                 "${archive}.partial" EXPECTED_HASH "SHA256=${MOBIINFER_ARCHIVE_SHA256}"
                 TLS_VERIFY ON TIMEOUT 600 INACTIVITY_TIMEOUT 60 SHOW_PROGRESS)
            file(RENAME "${archive}.partial" "${archive}")
        endif()
        # Exclude Android demo, SDKs, and Unix-only symlinks in unrelated tools.
        file(ARCHIVE_EXTRACT INPUT "${archive}" DESTINATION "${cache}" PATTERNS
             "${prefix}/CMakeLists.txt" "${prefix}/LICENSE" "${prefix}/cmake/*"
             "${prefix}/include/*" "${prefix}/src/*" "${prefix}/ggml/*"
             "${prefix}/vendor/nlohmann/*")
        file(TOUCH "${source}/.prepared-${MOBIINFER_ARCHIVE_SHA256}")
    endif()
    set(${output} "${source}" PARENT_SCOPE)
endfunction()
