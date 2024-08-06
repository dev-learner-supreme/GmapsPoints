package com.arun.gmapspoints;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import com.google.maps.android.SphericalUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final String TAG = "MainActivity";

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private List<LatLng> polygonPoints = new ArrayList<>();
    private Polygon polygon;
    private TextView areaTextView;
    private FirebaseAuth mAuth;
    private FirebaseUser currentUser;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAuth = FirebaseAuth.getInstance();
        currentUser = mAuth.getCurrentUser();

        if (currentUser == null) {
            // User is not signed in, redirect to SignInActivity
            Intent signInIntent = new Intent(MainActivity.this, SignInActivity.class);
            startActivity(signInIntent);
            finish(); // Close the MainActivity so user cannot return to it without signing in
            return;
        }
        setContentView(R.layout.activity_main);

        areaTextView = findViewById(R.id.tv_area);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        db = FirebaseFirestore.getInstance();

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map_container);
        mapFragment.getMapAsync(this);

        findViewById(R.id.fab_open_directory).setOnClickListener(view -> openFileList());
        findViewById(R.id.fab_refresh).setOnClickListener(view -> refreshMap());
        findViewById(R.id.fab_save).setOnClickListener(view -> saveMarkersToFirestore());
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        updateLocationUI();
        getDeviceLocation();
        mMap.setOnMapClickListener(latLng -> {
            mMap.addMarker(new MarkerOptions().position(latLng));
            polygonPoints.add(latLng);
            updatePolygon();
        });
    }

    private void updateLocationUI() {
        if (mMap == null) {
            return;
        }
        try {
            mMap.setMyLocationEnabled(true);
        } catch (SecurityException e) {
            Log.e(TAG, "Exception: %s", e);
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show();
        }
    }

    private void getDeviceLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }
        fusedLocationClient.getLastLocation()
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        Location location = task.getResult();
                        LatLng currentLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 20));
                    } else {
                        Log.d(TAG, "Current location is null. Using defaults.");
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(0, 0), 15));
                        Toast.makeText(this, "Current location is null", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void updatePolygon() {
        if (polygon != null) {
            polygon.remove();
        }
        if (polygonPoints.size() > 1) {
            PolygonOptions polygonOptions = new PolygonOptions()
                    .addAll(polygonPoints)
                    .strokeColor(Color.BLACK)
                    .fillColor(0x30ff0000);
            polygon = mMap.addPolygon(polygonOptions);
            double area = calculatePolygonArea(polygonPoints);
            double areaInHectares = area / 10000; // 1 hectare = 10,000 square meters
            areaTextView.setText(String.format(Locale.getDefault(), "Area: %.2f sq m (%.2f ha)", area, areaInHectares));
            Toast.makeText(this, "Area updated", Toast.LENGTH_SHORT).show();
        } else {
            areaTextView.setText("Area: 0.0 sq m (0.0 ha)");
            Toast.makeText(this, "Add more points to calculate area", Toast.LENGTH_SHORT).show();
        }
    }

    private double calculatePolygonArea(List<LatLng> latLngs) {
        if (latLngs.size() < 3) {
            return 0.0;
        }
        return SphericalUtil.computeArea(latLngs);
    }

    private void refreshMap() {
        polygonPoints.clear();
        updatePolygon();
        mMap.clear();
        Toast.makeText(this, "Map refreshed", Toast.LENGTH_SHORT).show();
    }

    private void saveMarkersToFirestore() {
        if (currentUser == null) {
            Toast.makeText(MainActivity.this, "User not authenticated", Toast.LENGTH_SHORT).show();
            return;
        }

        String username = getUsernameFromEmail(currentUser.getEmail()); // Extract username
        if (username == null || username.isEmpty()) {
            Toast.makeText(MainActivity.this, "Username extraction failed", Toast.LENGTH_SHORT).show();
            return;
        }

        CollectionReference markerDataRef = db.collection("users").document(username).collection("farms");

        markerDataRef.get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                int fileCount = task.getResult().size();
                String documentId = "farm" + (fileCount + 1);
                Map<String, Object> markerData = new HashMap<>();
                List<Map<String, Object>> points = new ArrayList<>();

                for (LatLng point : polygonPoints) {
                    Map<String, Object> pointData = new HashMap<>();
                    pointData.put("latitude", point.latitude);
                    pointData.put("longitude", point.longitude);
                    points.add(pointData);
                }

                markerData.put("points", points);

                markerDataRef.document(documentId)
                        .set(markerData)
                        .addOnSuccessListener(aVoid -> {
                            Toast.makeText(MainActivity.this, "Markers saved to Firestore", Toast.LENGTH_SHORT).show();
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Error saving markers to Firestore: " + e.getMessage(), e);
                            Toast.makeText(MainActivity.this, "Error saving markers to Firestore: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
            } else {
                Log.e(TAG, "Error getting documents: " + task.getException(), task.getException());
                Toast.makeText(MainActivity.this, "Error getting documents from Firestore: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void openFileList() {
        String username = getUsernameFromEmail(currentUser.getEmail()); // Extract username
        if (username == null || username.isEmpty()) {
            Toast.makeText(this, "Username extraction failed", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("users").document(username).collection("farms")
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        List<String> documentIds = new ArrayList<>();
                        for (DocumentSnapshot document : task.getResult()) {
                            documentIds.add(document.getId());
                        }
                        if (documentIds.isEmpty()) {
                            Toast.makeText(this, "No farms found", Toast.LENGTH_SHORT).show();
                        } else {
                            showFileListDialog(documentIds);
                        }
                    } else {
                        Log.e(TAG, "Error getting documents: " + task.getException(), task.getException());
                        Toast.makeText(this, "Error getting documents from Firestore", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void showFileListDialog(List<String> documentIds) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, documentIds);
        ListView listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String selectedFileId = documentIds.get(position);
            loadMarkersFromFirestore(selectedFileId);
        });

        new AlertDialog.Builder(this)
                .setTitle("Select a Farm")
                .setView(listView)
                .setPositiveButton("Cancel", null)
                .show();
    }

    private void loadMarkersFromFirestore(String documentId) {
        String username = getUsernameFromEmail(currentUser.getEmail()); // Extract username
        if (username == null || username.isEmpty()) {
            Toast.makeText(this, "Username extraction failed", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("users").document(username).collection("farms").document(documentId)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        DocumentSnapshot document = task.getResult();
                        if (document.exists()) {
                            List<Map<String, Object>> points = (List<Map<String, Object>>) document.get("points");
                            polygonPoints.clear();
                            mMap.clear();
                            if (points != null) {
                                for (Map<String, Object> pointData : points) {
                                    double lat = (double) pointData.get("latitude");
                                    double lng = (double) pointData.get("longitude");
                                    LatLng latLng = new LatLng(lat, lng);
                                    polygonPoints.add(latLng);
                                    mMap.addMarker(new MarkerOptions().position(latLng));
                                }
                                updatePolygon();
                            }
                        } else {
                            Toast.makeText(this, "No such document", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Log.e(TAG, "Error getting document: " + task.getException(), task.getException());
                        Toast.makeText(this, "Error getting document from Firestore", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private String getUsernameFromEmail(String email) {
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf("@"));
        }
        return null;
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                updateLocationUI();
                getDeviceLocation();
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
