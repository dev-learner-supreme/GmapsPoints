package com.arun.gmapspoints;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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
import com.google.maps.android.SphericalUtil;  // Import SphericalUtil
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import android.app.AlertDialog;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final int STORAGE_PERMISSION_REQUEST_CODE = 2;
    private static final String TAG = "MainActivity";

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private List<LatLng> polygonPoints = new ArrayList<>();
    private Polygon polygon;
    private TextView areaTextView;
    private FirebaseStorage firebaseStorage;
    private StorageReference storageReference;
    private FirebaseAuth mAuth;
    private FirebaseUser currentUser;

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
        firebaseStorage = FirebaseStorage.getInstance();
        storageReference = firebaseStorage.getReference().child("json");

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map_container);
        mapFragment.getMapAsync(this);

        findViewById(R.id.fab_open_directory).setOnClickListener(view -> openFileList());

        findViewById(R.id.fab_refresh).setOnClickListener(view -> refreshMap());

        findViewById(R.id.fab_save).setOnClickListener(view -> getNextFileNumber());
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
        // Use SphericalUtil to calculate the area of the polygon
        return SphericalUtil.computeArea(latLngs);
    }

    private void refreshMap() {
        polygonPoints.clear();
        updatePolygon();
        mMap.clear();
        Toast.makeText(this, "Map refreshed", Toast.LENGTH_SHORT).show();
    }

    private void getNextFileNumber() {
        final int[] maxFileNumber = {0}; // Array to hold the maximum file number

        // List all files in the "json" folder
        storageReference.listAll().addOnSuccessListener(listResult -> {
            List<String> fileNames = new ArrayList<>();
            for (StorageReference item : listResult.getItems()) {
                fileNames.add(item.getName());
            }

            // Determine the highest number in the file names
            for (String fileName : fileNames) {
                if (fileName.matches("farm(\\d+)\\.json")) {
                    int number = Integer.parseInt(fileName.replaceAll("farm(\\d+)\\.json", "$1"));
                    if (number >= maxFileNumber[0]) {
                        maxFileNumber[0] = number + 1;
                    }
                }
            }

            // If no files exist, start with 1
            if (maxFileNumber[0] == 0) {
                maxFileNumber[0] = 1;
            }

            // Proceed to save the markers with the new file number
            saveMarkers(maxFileNumber[0]);
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Error listing files from Firebase Storage: " + e.getMessage());
            Toast.makeText(this, "Error listing files from Firebase Storage", Toast.LENGTH_SHORT).show();
        });
    }

    private void saveMarkers(int fileNumber) {
        JSONArray jsonArray = new JSONArray();
        for (int i = 0; i < polygonPoints.size(); i++) {
            LatLng point = polygonPoints.get(i);
            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put("point_number", i + 1);
                jsonObject.put("latitude", point.latitude);
                jsonObject.put("longitude", point.longitude);
                jsonArray.put(jsonObject);
            } catch (JSONException e) {
                Log.e(TAG, "Error adding point to JSON: " + e.getMessage());
                Toast.makeText(this, "Error adding point to JSON", Toast.LENGTH_SHORT).show();
            }
        }
        JSONObject finalJson = new JSONObject();
        try {
            finalJson.put("points", jsonArray);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating final JSON: " + e.getMessage());
            Toast.makeText(this, "Error creating final JSON", Toast.LENGTH_SHORT).show();
        }

        // Upload to Firebase Storage
        String fileName = "farm" + fileNumber + ".json";
        StorageReference fileRef = storageReference.child(fileName);
        byte[] data = finalJson.toString().getBytes();
        UploadTask uploadTask = fileRef.putBytes(data);
        uploadTask.addOnSuccessListener(taskSnapshot -> {
            Toast.makeText(this, "Data saved to Firebase Storage", Toast.LENGTH_SHORT).show();
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Error saving JSON file to Firebase Storage: " + e.getMessage());
            Toast.makeText(this, "Error saving JSON file to Firebase Storage", Toast.LENGTH_SHORT).show();
        });
    }

    private void openFileList() {
        storageReference.listAll().addOnSuccessListener(listResult -> {
            List<String> fileNames = new ArrayList<>();
            for (StorageReference item : listResult.getItems()) {
                fileNames.add(item.getName());
            }

            // Show file list in a dialog
            showFileListDialog(fileNames);
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Error listing files from Firebase Storage: " + e.getMessage());
            Toast.makeText(this, "Error listing files from Firebase Storage", Toast.LENGTH_SHORT).show();
        });
    }

    private void showFileListDialog(List<String> fileNames) {
        // Create a dialog with a ListView to show files
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select a file");

        ListView listView = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, fileNames);
        listView.setAdapter(adapter);

        builder.setView(listView);
        AlertDialog dialog = builder.create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String selectedFileName = fileNames.get(position);
            downloadFile(selectedFileName);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void downloadFile(String fileName) {
        StorageReference fileRef = storageReference.child(fileName);
        fileRef.getBytes(Long.MAX_VALUE).addOnSuccessListener(bytes -> {
            String jsonData = new String(bytes);
            parseJsonData(jsonData);
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Error downloading file from Firebase Storage: " + e.getMessage());
            Toast.makeText(this, "Error downloading file from Firebase Storage", Toast.LENGTH_SHORT).show();
        });
    }

    private void parseJsonData(String jsonData) {
        try {
            JSONObject jsonObject = new JSONObject(jsonData);
            JSONArray pointsArray = jsonObject.getJSONArray("points");

            // Clear existing markers and polygon
            mMap.clear();
            polygonPoints.clear();

            LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();

            for (int i = 0; i < pointsArray.length(); i++) {
                JSONObject pointObject = pointsArray.getJSONObject(i);
                double latitude = pointObject.getDouble("latitude");
                double longitude = pointObject.getDouble("longitude");
                LatLng point = new LatLng(latitude, longitude);
                polygonPoints.add(point);
                boundsBuilder.include(point); // Add each point to the bounds builder
                // Add markers for each point
                mMap.addMarker(new MarkerOptions().position(point));
            }

            updatePolygon();

            // Get the bounds and update the camera to fit the bounds
            LatLngBounds bounds = boundsBuilder.build();
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100)); // 100 is padding in pixels

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSON data: " + e.getMessage());
            Toast.makeText(this, "Error parsing JSON data", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                updateLocationUI();
                getDeviceLocation();
            } else {
                Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Handle storage permissions if needed
            } else {
                Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
