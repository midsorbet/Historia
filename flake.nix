{
  description = "Historia Flake";
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-24.05";
    flake-utils.url = "github:numtide/flake-utils";
  };
  outputs = { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ ];
        };
      in
      {
        formatter = pkgs.nixpkgs-fmt;
        devShell = pkgs.mkShell rec {
          buildInputs = with pkgs; [
          #   graalvmCEPackages.graalnodejs
          #   graalvmCEPackages.graaljs
          #   python3
            zlib
            libzip
          ];
          packages = with pkgs; [
            nixd
          ];
          shellHook = ''
          # export JAVA_HOME=/home/me/.mx/jdks/labsjdk-ce-17-jvmci-23.1-b02/bin
          export GRAAL_HOME=$(realpath ./graal/sdk/latest_graalvm_home)
          export LD_LIBRARY_PATH="${pkgs.lib.makeLibraryPath buildInputs}:$LD_LIBRARY_PATH"
          '';
        };
      });
}
